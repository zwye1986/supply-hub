package com.aifuli.module.supplychain.border.fulfillment.application;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

class ProjectStandardAssetAuditIntegrationTest {
    static ProjectStandardQueryIntegrationSupport db;

    @BeforeAll
    static void start() {
        db = new ProjectStandardQueryIntegrationSupport();
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @BeforeEach
    void reset() {
        db.resetData();
    }

    @Test
    void successAuditsApplicationAndDecryptWithNoUserDigestAndOuterRollbackKeepsAudit() {
        assertThat(AopUtils.isAopProxy(db.queries())).isTrue();
        assertThat(AopUtils.isAopProxy(db.context.getBean(ProjectStandardAssetAuditService.class)))
                .isTrue();
        db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.asset(10, 1, 2, "VOUCHER_CODE", (short) 10);
        var bytes = "{\"cardCode\":\"voucher-value\"}".getBytes(StandardCharsets.UTF_8);
        when(db.crypto().decrypt(any(), any(), any())).thenReturn(bytes);
        db.transaction()
                .executeWithoutResult(
                        status -> {
                            var response = db.assets().query(db.assetContext(3, 2), "order");
                            assertThat(response.getAssets()).hasSize(1);
                            assertThat(response.getAssets().getFirst().getVoucherCode())
                                    .isEqualTo("voucher-value");
                            assertThat(response.getHint()).isNull();
                            status.setRollbackOnly();
                        });
        assertThat(bytes).containsOnly((byte) 0);
        var rows = db.jdbc.queryForList("SELECT * FROM sc_sensitive_access_audit ORDER BY id");
        assertThat(rows).hasSize(2);
        for (var row : rows) {
            assertThat(row)
                    .containsEntry("principal_type", "PROJECT_APPLICATION")
                    .containsEntry("principal_id", "project-app")
                    .containsEntry("project_id", 3L)
                    .containsEntry("buyer_id", 2L)
                    .containsEntry("app_key_version", 7)
                    .containsEntry("order_no", "SC_1")
                    .containsEntry("scope_code", "STANDARD_ORDER_ASSET_QUERY")
                    .containsEntry("buyer_user_digest", null);
            assertThat(row.get("trace_id").toString())
                    .startsWith("AUD_")
                    .hasSizeLessThanOrEqualTo(32);
        }
        assertThat(rows.getFirst())
                .containsEntry("access_type", "ASSET_DECRYPT")
                .containsEntry("resource_type", "FULFILLMENT_ASSET")
                .containsEntry("resource_id", 10L);
        assertThat(rows.getLast())
                .containsEntry("access_type", "ASSET_QUERY")
                .containsEntry("resource_type", "FULFILLMENT_REQUEST")
                .containsEntry("resource_id", 1L);
    }

    @Test
    void waitManualRejectUnsupportedTerminalAndNotFoundAllHaveCommittedQueryAudit() {
        db.receipt(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        var wait = db.assets().query(db.assetContext(3, 2), "order");
        assertThat(wait.getAssets()).isEmpty();
        assertThat(wait.getOrderNo()).isNull();
        assertThat(wait.getHint()).isEqualTo("请等待，稍后重新获卡");
        audit("WAITING", 10, 0);
        db.resetData();
        db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.jdbc.update("UPDATE sc_supply_chain_order SET order_status=90 WHERE id=1");
        db.jdbc.update("UPDATE sc_fulfillment_request SET status=90 WHERE id=1");
        var manual = db.assets().query(db.assetContext(3, 2), "order");
        assertThat(manual.getHint()).isEqualTo("订单正在人工处理中，请通过查单接口查看进度");
        audit("MANUAL_WAITING", 10, 1);
        db.resetData();
        db.receipt(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.jdbc.update(
                "UPDATE sc_fulfillment_acceptance_receipt SET"
                    + " status=30,rejection_code='INVALID',rejected_at=CURRENT_TIMESTAMP(3) WHERE"
                    + " id=1");
        assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        e ->
                                assertThat(e.getCode())
                                        .isEqualTo(ASSET_QUERY_ORDER_NOT_ACCEPTED.getCode()));
        audit("ORDER_NOT_ACCEPTED", 20, 0);
        db.resetData();
        db.accepted(1, 3L, 2, "order", "DIRECT_RECHARGE_TARGET");
        db.jdbc.update(
                "UPDATE sc_fulfillment_request SET status=40,fulfilled_quantity=1 WHERE id=1");
        assertThat(db.queries().query(db.queryContext(3, 2), "order").getAssetAvailable())
                .isFalse();
        assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        e ->
                                assertThat(e.getCode())
                                        .isEqualTo(ASSET_QUERY_UNSUPPORTED_PRODUCT_TYPE.getCode()));
        audit("UNSUPPORTED_PRODUCT_TYPE", 20, 1);
        for (int terminal : new int[] {40, 50}) {
            db.resetData();
            db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
            db.asset(10, 1, 2, "VOUCHER_CODE", (short) 10);
            db.jdbc.update("UPDATE sc_supply_chain_order SET order_status=? WHERE id=1", terminal);
            assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                    .isInstanceOfSatisfying(
                            ServiceException.class,
                            e ->
                                    assertThat(e.getCode())
                                            .isEqualTo(ASSET_QUERY_ORDER_TERMINATED.getCode()));
            audit("ORDER_TERMINATED", 20, 1);
            verifyNoInteractions(db.crypto());
        }
        db.resetData();
        db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        assertThatThrownBy(() -> db.assets().query(db.assetContext(4, 2), "order"))
                .isInstanceOf(ServiceException.class);
        audit("ORDER_NOT_FOUND", 20, 0);
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT order_no FROM sc_sensitive_access_audit", String.class))
                .isNull();
    }

    @Test
    void decryptFailureAndJsonFailureCommitAccuratePerAssetEventsAndNeverPartialResults() {
        for (boolean cryptoFailure : new boolean[] {true, false}) {
            db.resetData();
            db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
            db.asset(10, 1, 2, "VOUCHER_CODE", (short) 10);
            db.asset(11, 1, 2, "VOUCHER_CODE", (short) 10);
            var bytes = new ArrayList<byte[]>();
            when(db.crypto().decrypt(any(), any(), any()))
                    .thenAnswer(
                            call -> {
                                byte[] encrypted = call.getArgument(0);
                                if (encrypted[0] == 11 && cryptoFailure)
                                    throw new IllegalStateException("external-sensitive-message");
                                byte[] value =
                                        (encrypted[0] == 10
                                                        ? "{\"voucherCode\":\"first-secret\"}"
                                                        : "{broken-secret")
                                                .getBytes(StandardCharsets.UTF_8);
                                bytes.add(value);
                                return value;
                            });
            assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                    .isInstanceOf(ProjectStandardAssetQueryFailure.class)
                    .hasMessageNotContaining("secret");
            for (var value : bytes) assertThat(value).containsOnly((byte) 0);
            var rows =
                    db.jdbc.queryForList(
                            "SELECT access_type,resource_id,result_status,reason_code FROM"
                                    + " sc_sensitive_access_audit ORDER BY id");
            assertThat(rows).hasSize(3);
            assertThat(((Number) rows.get(0).get("result_status")).intValue()).isEqualTo(10);
            assertThat(rows.get(0).get("resource_id")).isEqualTo(10L);
            assertThat(((Number) rows.get(1).get("result_status")).intValue()).isEqualTo(30);
            assertThat(rows.get(1).get("reason_code"))
                    .isEqualTo(cryptoFailure ? "DECRYPT_FAILED" : "PAYLOAD_FAILED");
            assertThat(rows.get(1).get("resource_id")).isEqualTo(11L);
            assertThat(((Number) rows.get(2).get("result_status")).intValue()).isEqualTo(30);
            assertThat(rows.get(2).get("access_type")).isEqualTo("ASSET_QUERY");
        }
    }

    /** 真实事务验证已知字段非法值按载荷失败审计，已解密明文不得部分交付。 */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"assetType\":null,\"voucherCode\":\"second-secret\"}",
                "{\"cardSecret\":[],\"voucherCode\":\"second-secret\"}"
            })
    void invalidKnownPayloadFieldsCommitFailedAuditAndEraseAllPlaintext(String invalidPayload) {
        db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.asset(10, 1, 2, "VOUCHER_CODE", (short) 10);
        db.asset(11, 1, 2, "VOUCHER_CODE", (short) 10);
        var first = "{\"voucherCode\":\"first-secret\"}".getBytes(StandardCharsets.UTF_8);
        var invalid = invalidPayload.getBytes(StandardCharsets.UTF_8);
        when(db.crypto().decrypt(any(), any(), any()))
                .thenAnswer(
                        call -> {
                            byte[] encrypted = call.getArgument(0);
                            return encrypted[0] == 10 ? first : invalid;
                        });
        assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                .isInstanceOf(ProjectStandardAssetQueryFailure.class)
                .hasMessageNotContaining("secret");
        assertThat(first).containsOnly((byte) 0);
        assertThat(invalid).containsOnly((byte) 0);
        var rows =
                db.jdbc.queryForList(
                        "SELECT access_type,resource_id,result_status,reason_code FROM"
                                + " sc_sensitive_access_audit ORDER BY id");
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst())
                .containsEntry("access_type", "ASSET_DECRYPT")
                .containsEntry("resource_id", 10L)
                .containsEntry("reason_code", "AUTHORIZED_QUERY");
        assertThat(((Number) rows.getFirst().get("result_status")).intValue()).isEqualTo(10);
        assertThat(rows.get(1))
                .containsEntry("access_type", "ASSET_DECRYPT")
                .containsEntry("resource_id", 11L)
                .containsEntry("reason_code", "PAYLOAD_FAILED");
        assertThat(((Number) rows.get(1).get("result_status")).intValue()).isEqualTo(30);
        assertThat(rows.getLast())
                .containsEntry("access_type", "ASSET_QUERY")
                .containsEntry("resource_id", 1L)
                .containsEntry("reason_code", "PAYLOAD_FAILED");
        assertThat(((Number) rows.getLast().get("result_status")).intValue()).isEqualTo(30);
        assertThat(rows.toString()).doesNotContain("first-secret", "second-secret");
    }

    @Test
    void auditInsertFaultFailsClosedAndKeepsOriginalBusinessFailureSuppressed() {
        db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.asset(10, 1, 2, "VOUCHER_CODE", (short) 10);
        when(db.crypto().decrypt(any(), any(), any()))
                .thenReturn("{\"voucherCode\":\"secret\"}".getBytes(StandardCharsets.UTF_8));
        db.jdbc.execute(
                "CREATE TRIGGER fail_project_audit BEFORE INSERT ON sc_sensitive_access_audit FOR"
                        + " EACH ROW BEGIN IF NEW.access_type='ASSET_QUERY' THEN SIGNAL SQLSTATE"
                        + " '45000' SET MESSAGE_TEXT='audit unavailable'; END IF; END");
        try {
            assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("敏感访问审计写入失败，安全拒绝返回")
                    .hasCauseInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> db.assets().query(db.assetContext(4, 2), "order"))
                    .isInstanceOfSatisfying(
                            IllegalStateException.class,
                            e -> {
                                assertThat(e.getCause()).isNotNull();
                                assertThat(e.getSuppressed()).hasSize(1);
                                assertThat(e.getSuppressed()[0])
                                        .isInstanceOf(ServiceException.class);
                            });
            assertThat(
                            db.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sc_sensitive_access_audit", Long.class))
                    .isZero();
        } finally {
            db.jdbc.execute("DROP TRIGGER fail_project_audit");
        }
    }

    @Test
    void expiredQuarantinedFutureAndVoidedAssetsNeverDecryptAndSucceededEmptyFailsClosed() {
        for (int lifecycle : new int[] {30, 40, 50, 90}) {
            db.resetData();
            db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
            db.asset(10, 1, 2, "VOUCHER_CODE", (short) lifecycle);
            assertThat(db.assets().query(db.assetContext(3, 2), "order").getAssets()).isEmpty();
            verifyNoInteractions(db.crypto());
            audit("WAITING", 10, 1);
        }
        for (String validity :
                new String[] {
                    "valid_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY)",
                    "valid_from=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY)"
                }) {
            db.resetData();
            db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
            db.asset(10, 1, 2, "VOUCHER_CODE", (short) 10);
            db.jdbc.update("UPDATE sc_fulfillment_asset SET " + validity + " WHERE id=10");
            assertThat(db.assets().query(db.assetContext(3, 2), "order").getAssets()).isEmpty();
            verifyNoInteractions(db.crypto());
            audit("WAITING", 10, 1);
        }
        db.resetData();
        db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.jdbc.update("UPDATE sc_supply_chain_order SET order_status=30 WHERE id=1");
        db.jdbc.update(
                "UPDATE sc_fulfillment_request SET status=40,fulfilled_quantity=1 WHERE id=1");
        assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                .isInstanceOf(ProjectStandardAssetQueryFailure.class);
        audit("REFERENCE_INCONSISTENT", 30, 1);
    }

    @Test
    void queryingNeverChangesMoneyTimerOrLifecycleAndDoesNotWaitForExecutionRowLocks()
            throws Exception {
        db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.asset(10, 1, 2, "VOUCHER_CODE", (short) 10);
        when(db.crypto().decrypt(any(), any(), any()))
                .thenAnswer(c -> "{\"voucherCode\":\"secret\"}".getBytes(StandardCharsets.UTF_8));
        var counts =
                db.jdbc.queryForList(
                        "SELECT 'timer' kind,COUNT(*) amount FROM sc_timer_task UNION ALL SELECT"
                                + " 'ledger',COUNT(*) FROM sc_account_ledger UNION ALL SELECT"
                                + " 'hold',COUNT(*) FROM sc_fund_reservation");
        try (var lock =
                db.context
                        .getBean("supplyChainDataSource", javax.sql.DataSource.class)
                        .getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) {
                statement.executeQuery("SELECT id FROM sc_b_order WHERE id=10 FOR UPDATE");
            }
            assertThat(db.assets().query(db.assetContext(3, 2), "order").getAssets()).hasSize(1);
            lock.rollback();
        }
        assertThat(
                        db.jdbc.queryForList(
                                "SELECT 'timer' kind,COUNT(*) amount FROM sc_timer_task UNION ALL"
                                    + " SELECT 'ledger',COUNT(*) FROM sc_account_ledger UNION ALL"
                                    + " SELECT 'hold',COUNT(*) FROM sc_fund_reservation"))
                .isEqualTo(counts);
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT lifecycle_status FROM sc_fulfillment_asset WHERE id=10",
                                Integer.class))
                .isEqualTo(10);
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT buyer_settlement_amount FROM sc_supply_chain_order WHERE"
                                        + " id=1",
                                Long.class))
                .isEqualTo(100L);
    }

    @Test
    void auditUpgradePreservesLegacyRowsAndExactNewColumnsChecksAndIndex() throws Exception {
        try (var connection =
                java.sql.DriverManager.getConnection(
                        db.mysql.getJdbcUrl(), "root", db.mysql.getPassword())) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE audit_upgrade");
            }
            String originalCatalog = connection.getCatalog();
            connection.setCatalog("audit_upgrade");
            try {
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(
                                "db/changelog/releases/1.0.0/010-sensitive-access-audit.sql"));
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(
                            "INSERT INTO"
                                + " sc_sensitive_access_audit(audit_no,access_type,principal_type,principal_id,resource_type,resource_id,scope_code,result_status,reason_code,trace_id,accessed_at)"
                                + " VALUES('AUD_legacy','ASSET_QUERY','BUYER_SERVICE','old','FULFILLMENT_REQUEST',1,'FULFILLMENT_ASSET_READ',10,'AUTHORIZED_QUERY','trace',CURRENT_TIMESTAMP(3))");
                }
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(
                                "db/changelog/releases/1.0.0/050-project-standard-asset-audit.sql"));
                try (var statement = connection.createStatement();
                        var rows =
                                statement.executeQuery(
                                        "SELECT principal_type,project_id,order_no,app_key_version"
                                                + " FROM sc_sensitive_access_audit WHERE"
                                                + " audit_no='AUD_legacy'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("BUYER_SERVICE");
                    assertThat(rows.getObject(2)).isNull();
                    assertThat(rows.getObject(3)).isNull();
                    assertThat(rows.getObject(4)).isNull();
                }
                try (var statement = connection.createStatement();
                        var column =
                                statement.executeQuery(
                                        "SELECT"
                                            + " column_comment,data_type,is_nullable,datetime_precision"
                                            + " FROM information_schema.columns WHERE"
                                            + " table_schema='audit_upgrade' AND"
                                            + " table_name='sc_sensitive_access_audit' AND"
                                            + " column_name='accessed_at'")) {
                    assertThat(column.next()).isTrue();
                    assertThat(column.getString("column_comment"))
                            .isEqualTo("访问发生时间，Asia/Shanghai");
                    assertThat(column.getString("data_type")).isEqualTo("datetime");
                    assertThat(column.getString("is_nullable")).isEqualTo("NO");
                    assertThat(column.getInt("datetime_precision")).isEqualTo(3);
                }
            } finally {
                connection.setCatalog(originalCatalog);
            }
        }
        var columns =
                db.jdbc.queryForList(
                        "SELECT"
                            + " column_name,data_type,is_nullable,column_comment,character_maximum_length,datetime_precision"
                            + " FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                            + " table_name='sc_sensitive_access_audit'");
        var accessedAt =
                columns.stream()
                        .filter(c -> c.get("column_name").equals("accessed_at"))
                        .findFirst()
                        .orElseThrow();
        assertThat(accessedAt)
                .containsEntry("column_comment", "访问发生时间，Asia/Shanghai")
                .containsEntry("data_type", "datetime")
                .containsEntry("is_nullable", "NO");
        assertThat(((Number) accessedAt.get("datetime_precision")).intValue()).isEqualTo(3);
        for (var expected :
                java.util.Map.of(
                                "project_id",
                                "相关采购项目内部主键，无项目上下文时为空",
                                "order_no",
                                "相关中台订单稳定业务编号，尚未建单时为空",
                                "app_key_version",
                                "项目应用认证凭证版本，非项目应用访问时为空")
                        .entrySet()) {
            var row =
                    columns.stream()
                            .filter(c -> c.get("column_name").equals(expected.getKey()))
                            .findFirst()
                            .orElseThrow();
            assertThat(row.get("column_comment")).isEqualTo(expected.getValue());
            assertThat(row.get("is_nullable")).isEqualTo("YES");
            assertThat(row.get("data_type"))
                    .isEqualTo(
                            switch (expected.getKey()) {
                                case "project_id" -> "bigint";
                                case "order_no" -> "varchar";
                                case "app_key_version" -> "int";
                                default -> throw new IllegalStateException("未知schema字段");
                            });
            if (expected.getKey().equals("order_no"))
                assertThat(((Number) row.get("character_maximum_length")).longValue())
                        .isEqualTo(32L);
        }
        assertThat(
                        columns.stream()
                                .filter(c -> c.get("column_name").equals("principal_type"))
                                .findFirst()
                                .orElseThrow()
                                .get("column_comment"))
                .isEqualTo(
                        "访问主体类型：BUYER_SERVICE采购商服务，ADMIN_USER运营人员，SYSTEM_WORKER系统任务，PROJECT_APPLICATION项目应用");
        assertThat(
                        columns.stream()
                                .filter(c -> c.get("column_name").equals("scope_code"))
                                .findFirst()
                                .orElseThrow()
                                .get("column_comment"))
                .isEqualTo("授权访问所使用的权限Scope或操作编码");
        assertThat(
                        db.jdbc.queryForList(
                                "SELECT column_name FROM information_schema.statistics WHERE"
                                    + " table_schema=DATABASE() AND"
                                    + " table_name='sc_sensitive_access_audit' AND"
                                    + " index_name='idx_sc_sensitive_access_project_time' ORDER BY"
                                    + " seq_in_index",
                                String.class))
                .containsExactly("project_id", "accessed_at");
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT check_clause FROM information_schema.check_constraints"
                                        + " WHERE constraint_schema=DATABASE() AND"
                                        + " constraint_name='ck_sc_sensitive_access_principal'",
                                String.class))
                .contains("PROJECT_APPLICATION", "BUYER_SERVICE", "ADMIN_USER", "SYSTEM_WORKER");
    }

    private void audit(String reason, int status, long resource) {
        var row =
                db.jdbc.queryForMap(
                        "SELECT access_type,reason_code,result_status,resource_id FROM"
                                + " sc_sensitive_access_audit ORDER BY id DESC LIMIT 1");
        assertThat(row)
                .containsEntry("access_type", "ASSET_QUERY")
                .containsEntry("reason_code", reason)
                .containsEntry("resource_id", resource);
        assertThat(((Number) row.get("result_status")).intValue()).isEqualTo(status);
    }
}
