package com.aifuli.module.supplychain.border.fulfillment;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.PreconditionFailedException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 在真实 MySQL 上验证 {@code 052-standard-order-project-key} 的分阶段升级：
 * 非法历史（重复项目键、旧唯一键保留场景重复、STANDARD_REQUEST 路由主体键映射冲突、回执/总单/项目归属链断裂）
 * 在破坏性 DDL 前触发 Liquibase HALT 且旧数据/旧列不被修改；合法历史升级后购买人身份列删除、
 * 双生成列唯一键与 CHECK 生效、历史 CARD 购买人及旧含用户 hash 清空、路由主体键转全局订单号、
 * 频率覆盖门禁重置 CLOSED。空库全量升级与二次幂等由 {@code SupplyChainLiquibaseIntegrationTest} 覆盖。
 * 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#5-052增量历史清理与安全覆盖。
 */
@Testcontainers(disabledWithoutDocker = true)
class StandardOrderProjectKeyMigrationIntegrationTest {

    private static final String RECEIPT = "sc_fulfillment_acceptance_receipt";
    private static final String ORDER = "sc_supply_chain_order";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    @Test
    void haltsOnIllegalHistoryThenUpgradesLegalHistory() throws Exception {
        // 1. 先迁移到 051（052 之前共 53 个变更集），保持旧 schema 供历史数据构造。
        liquibaseUpdate(53);
        assertThat(columnExists(RECEIPT, "buyer_user_id")).isTrue();
        assertThat(columnExists(ORDER, "buyer_user_id")).isTrue();
        // 门禁先置 READY：052 必须重置为 CLOSED，HALT 期间也不得被改动。
        setGateReady();
        insertBuyerProject();

        // 2. 场景A：重复项目键——放下旧唯一键模拟键缺失/漂移后，插入同 (project, buyerOrderNo) 的
        //    两条回执与两条 CARD 总单（回执↔总单链路本身一致），预条件必须 HALT。
        dropOldBusinessUniqueKeys();
        insertAcceptedReceipt(261L, 801L, "ORD-DUP-PROJECT", 201L, "SC0520201", "FR0520261");
        insertAcceptedReceipt(262L, 801L, "ORD-DUP-PROJECT", 202L, "SC0520202", "FR0520262");
        execute(cardOrderSql(201L, "SC0520201", 801L, "ORD-DUP-PROJECT", 261L, "legacy-user-201"));
        execute(cardOrderSql(202L, "SC0520202", 801L, "ORD-DUP-PROJECT", 262L, "legacy-user-202"));
        assertNextChangesetHalts();
        assertHaltKeepsPreState("""
                SELECT COUNT(*) FROM sc_fulfillment_acceptance_receipt WHERE buyer_order_no = 'ORD-DUP-PROJECT'
                """, 2);
        assertHaltKeepsPreState("""
                SELECT COUNT(*) FROM sc_supply_chain_order WHERE buyer_order_no = 'ORD-DUP-PROJECT'
                """, 2);
        cleanupScenarioRows("'ORD-DUP-PROJECT'");
        restoreOldBusinessUniqueKeys();

        // 3. 场景B：旧唯一键保留场景重复——历史 NULL 项目回执与 INSTORE 总单各自出现
        //    同 (buyer, buyerOrderNo) 两条，legacy 保留键将无法保持唯一，HALT。
        dropOldBusinessUniqueKeys();
        insertRejectedReceipt(263L, null, "ORD-DUP-LEGACY", "NO_ROUTABLE_SUPPLIER");
        insertRejectedReceipt(264L, null, "ORD-DUP-LEGACY", "NO_ROUTABLE_SUPPLIER");
        execute(instoreOrderSql(203L, "SC0520203", "ORD-DUP-LEGACY", "legacy-user-203"));
        execute(instoreOrderSql(204L, "SC0520204", "ORD-DUP-LEGACY", "legacy-user-204"));
        assertNextChangesetHalts();
        assertHaltKeepsPreState("""
                SELECT COUNT(*) FROM sc_fulfillment_acceptance_receipt WHERE buyer_order_no = 'ORD-DUP-LEGACY'
                """, 2);
        assertHaltKeepsPreState("""
                SELECT COUNT(*) FROM sc_supply_chain_order WHERE buyer_order_no = 'ORD-DUP-LEGACY'
                """, 2);
        cleanupScenarioRows("'ORD-DUP-LEGACY'");
        restoreOldBusinessUniqueKeys();

        // 4. 场景C1：路由主体键映射冲突——已关联总单的轮次转换后的目标键与另一条无订单历史轮次的
        //    旧键相同（同 buyer 同序号），转换后 (buyer, subjectType, subjectKey, sequence) 唯一键冲突，HALT。
        insertAcceptedReceipt(270L, 801L, "ORD-ROUTE-CONFLICT", 210L, "SC0520210", "FR0520270");
        execute(cardOrderSql(210L, "SC0520210", 801L, "ORD-ROUTE-CONFLICT", 270L, "legacy-user-210"));
        insertRoutingRound(781L, "RR0520781", "ORD-ROUTE-CONFLICT", 210L, 10);
        insertRoutingRound(782L, "RR0520782", "SC0520210", null, 20);
        assertNextChangesetHalts();
        assertHaltKeepsPreState("""
                SELECT COUNT(*) FROM sc_supplier_routing_round WHERE id IN (781, 782)
                  AND routing_subject_key IN ('ORD-ROUTE-CONFLICT', 'SC0520210')
                """, 2);
        execute("DELETE FROM sc_supplier_routing_round WHERE id IN (781, 782)");
        cleanupScenarioRows("'ORD-ROUTE-CONFLICT'");

        // 5. 场景C2：路由轮次关联孤儿——order_id 指向不存在的总单，无法严格关联转换，HALT。
        insertRoutingRound(783L, "RR0520783", "ORD-ORPHAN-ROUND", 999L, 20);
        assertNextChangesetHalts();
        assertHaltKeepsPreState("""
                SELECT COUNT(*) FROM sc_supplier_routing_round WHERE id = 783
                  AND routing_subject_key = 'ORD-ORPHAN-ROUND' AND order_id = 999
                """, 1);
        execute("DELETE FROM sc_supplier_routing_round WHERE id = 783");

        // 6. 场景D1：CARD 总单关联回执缺失（归属链断裂），HALT。
        execute(cardOrderSql(220L, "SC0520220", 801L, "ORD-CHAIN-MISSING", 888L, "legacy-user-220"));
        assertNextChangesetHalts();
        assertHaltKeepsPreState("""
                SELECT COUNT(*) FROM sc_supply_chain_order WHERE id = 220 AND acceptance_receipt_id = 888
                """, 1);
        cleanupScenarioRows("'ORD-CHAIN-MISSING'");

        // 7. 场景D2：回执缺 project 而关联的历史共享 CARD 总单存在 project（回执↔总单项目归属不一致），HALT。
        insertAcceptedReceipt(271L, null, "ORD-CHAIN-PROJECT", 221L, "SC0520221", "FR0520271");
        execute(cardOrderSql(221L, "SC0520221", 801L, "ORD-CHAIN-PROJECT", 271L, "legacy-user-221"));
        assertNextChangesetHalts();
        assertHaltKeepsPreState("""
                SELECT COUNT(*) FROM sc_fulfillment_acceptance_receipt WHERE id = 271 AND project_id IS NULL
                """, 1);
        assertHaltKeepsPreState("""
                SELECT COUNT(*) FROM sc_supply_chain_order WHERE id = 221 AND project_id = 801
                """, 1);
        cleanupScenarioRows("'ORD-CHAIN-PROJECT'");

        // 8. 场景D3：回执项目归属断裂（project 指向不存在的项目），HALT。
        insertRejectedReceipt(272L, 999L, "ORD-ORPHAN-PROJECT", "LISTING_NOT_SALEABLE");
        assertNextChangesetHalts();
        assertHaltKeepsPreState("""
                SELECT COUNT(*) FROM sc_fulfillment_acceptance_receipt WHERE id = 272 AND project_id = 999
                """, 1);
        cleanupScenarioRows("'ORD-ORPHAN-PROJECT'");

        // 9. 清空非法历史后插入合法历史：CARD 项目订单、历史 NULL 项目共享单、INSTORE/O2O 真实用户订单、
        //    STANDARD_REQUEST 旧主体键路由轮次（含无候选轮次）、READY 门禁、含旧用户摘要审计与标准资产行。
        insertAcceptedReceipt(611L, 801L, "ORD-P1", 101L, "SC0520101", "FR0520611");
        insertAcceptedReceipt(622L, null, "ORD-LEGACY", 122L, "SC0520122", "FR0520622");
        insertAcceptedReceipt(631L, 801L, "ORD-ROUTE-OK", 111L, "SC0520111", "FR0520631");
        execute(cardOrderSql(101L, "SC0520101", 801L, "ORD-P1", 611L, "legacy-user-101"));
        execute(cardOrderSql(122L, "SC0520122", null, "ORD-LEGACY", 622L, "legacy-user-122"));
        execute(cardOrderSql(111L, "SC0520111", 801L, "ORD-ROUTE-OK", 631L, "legacy-user-111"));
        execute(instoreOrderSql(103L, "SC0520103", "RT-INST-052", "instore-real-user"));
        execute("""
                INSERT INTO sc_supply_chain_order (id, order_no, order_type, buyer_id, project_id,
                    buyer_code_snapshot, buyer_name_snapshot, buyer_user_id,
                    buyer_settlement_amount, currency, order_status, aftersale_status, business_stage, detail_type,
                    request_fingerprint, detail_version, created_at, updated_at)
                VALUES (104, 'SC0520104', 'O2O', 1, NULL, 'B052', '052迁移采购商', 'o2o-real-user', 5500, 'CNY',
                    10, 0, 'ACCEPTED', 'O2O_CHANNEL', UNHEX(SHA2('f-SC0520104', 256)), 1, NOW(3), NOW(3))
                """);
        insertRoutingRound(711L, "RR0520711", "ORD-ROUTE-OK", 111L, 10);
        insertRoutingRound(712L, "RR0520712", "ORD-NOORDER", null, 20);
        insertAsset(701L, "FA0520701", "legacy-asset-user");
        insertAudit(801L, "AUD0520801");
        liquibaseUpdate(1);

        // 10. 升级结果：回执与资产的购买人身份列删除；总订单购买人列保留但可空（INSTORE/O2O 继续真实写入）。
        assertThat(columnExists(RECEIPT, "buyer_user_id")).isFalse();
        assertThat(columnExists("sc_fulfillment_asset", "buyer_user_id")).isFalse();
        assertThat(columnExists(ORDER, "buyer_user_id")).isTrue();
        assertThat(isNullable(ORDER, "buyer_user_id")).isTrue();
        assertThat(columnExists(RECEIPT, "fingerprint_version")).isTrue();
        assertThat(columnExists(RECEIPT, "target_schema_version")).isTrue();
        assertThat(columnExists(RECEIPT, "platform_sku_id")).isTrue();
        assertThat(columnExists(RECEIPT, "pricing_quote_id")).isTrue();
        assertThat(columnExists(RECEIPT, "standard_project_key")).isTrue();
        assertThat(columnExists(RECEIPT, "legacy_buyer_key")).isTrue();
        assertThat(columnExists(ORDER, "fulfillment_account_type")).isTrue();
        assertThat(columnExists(ORDER, "remark")).isTrue();
        assertThat(columnExists(ORDER, "standard_project_key")).isTrue();
        assertThat(columnExists(ORDER, "legacy_buyer_key")).isTrue();
        // 冻结绑定列没有真实解析值时不补假数据：历史行全部为空、指纹格式版本标记为 0（历史未验证）。
        assertThat(isNullable(RECEIPT, "listing_version_no")).isTrue();
        assertThat(isNullable(RECEIPT, "platform_sku_id")).isTrue();
        assertThat(isNullable(RECEIPT, "target_schema_version")).isTrue();
        assertThat(isNullable(RECEIPT, "pricing_quote_id")).isTrue();
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_fulfillment_acceptance_receipt
                WHERE fingerprint_version = 0 AND listing_version_no = 3 AND platform_sku_id IS NULL
                  AND target_schema_version IS NULL AND pricing_quote_id IS NULL
                """)).isEqualTo(3);
        // 历史 CARD 购买人与旧含用户 hash/业务 digest 清 NULL；INSTORE/O2O 真实身份与请求指纹保留。
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_supply_chain_order
                WHERE order_type = 'CARD' AND (buyer_user_id IS NOT NULL
                   OR request_fingerprint IS NOT NULL OR buyer_order_digest IS NOT NULL)
                """)).isZero();
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_supply_chain_order
                WHERE id IN (103, 104) AND buyer_user_id IN ('instore-real-user', 'o2o-real-user')
                  AND request_fingerprint IS NOT NULL
                """)).isEqualTo(2);
        // 双生成列唯一键：项目 CARD 走项目键，其余订单/历史 NULL 项目走保留键，O2O 无单号不参与保留键。
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_supply_chain_order
                WHERE id = 101 AND standard_project_key = '801:ORD-P1' AND legacy_buyer_key IS NULL
                """)).isEqualTo(1);
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_supply_chain_order
                WHERE id = 122 AND standard_project_key IS NULL AND legacy_buyer_key = '1:ORD-LEGACY'
                """)).isEqualTo(1);
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_supply_chain_order
                WHERE id = 103 AND standard_project_key IS NULL AND legacy_buyer_key = '1:RT-INST-052'
                """)).isEqualTo(1);
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_supply_chain_order
                WHERE id = 104 AND standard_project_key IS NULL AND legacy_buyer_key IS NULL
                """)).isEqualTo(1);
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_fulfillment_acceptance_receipt
                WHERE id = 611 AND standard_project_key = '801:ORD-P1' AND legacy_buyer_key IS NULL
                """)).isEqualTo(1);
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_fulfillment_acceptance_receipt
                WHERE id = 622 AND standard_project_key IS NULL AND legacy_buyer_key = '1:ORD-LEGACY'
                """)).isEqualTo(1);
        assertThat(indexExists(RECEIPT, "uk_sc_fulfillment_receipt_business")).isFalse();
        assertThat(indexExists(ORDER, "uk_sc_order_buyer_business")).isFalse();
        assertThat(indexExists(RECEIPT, "uk_sc_fulfillment_receipt_project")).isTrue();
        assertThat(indexExists(RECEIPT, "uk_sc_fulfillment_receipt_legacy")).isTrue();
        assertThat(indexExists(ORDER, "uk_sc_order_project_key")).isTrue();
        assertThat(indexExists(ORDER, "uk_sc_order_legacy_key")).isTrue();
        // CHECK 生效：CARD 带购买人身份被拒、履约目标账户类型封闭字典、listing_version_no 0 被拒、
        // fingerprint_version 只允许 0/2；listing_version_no NULL 允许。
        assertSqlRejected(cardOrderSql(null, "SC0520CHK1", 801L, "ORD-CHK-CARD-USER", 698L, "should-be-rejected"));
        assertSqlRejected("""
                INSERT INTO sc_supply_chain_order (order_no, order_type, buyer_id, project_id,
                    buyer_code_snapshot, buyer_name_snapshot, buyer_user_id, buyer_order_no,
                    buyer_settlement_amount, currency, order_status, aftersale_status, business_stage, detail_type,
                    display_supplier_id, fulfillment_account_type, detail_version, created_at, updated_at)
                VALUES ('SC0520CHK2', 'INSTORE', 1, NULL, 'B052', '052迁移采购商', 'chk-user', 'RT-CHK-ACCOUNT',
                    8800, 'CNY', 10, 0, 'CHECK', 'INSTORE_CHANNEL', 1, 'WEIRD', 1, NOW(3), NOW(3))
                """);
        assertSqlRejected(processingReceiptSql("RCP0520CHK3", null, "ORD-CHK-VERSION", "0", "0"));
        assertSqlRejected(processingReceiptSql("RCP0520CHK4", null, "ORD-CHK-FORMAT", "1", "1"));
        execute(processingReceiptSql("RCP0520CHK5", null, "ORD-CHK-NULL-VERSION", "NULL", "0"));
        execute("DELETE FROM sc_fulfillment_acceptance_receipt WHERE receipt_no = 'RCP0520CHK5'");
        // 重复项目键/保留键插入撞双生成列唯一键（项目重复走项目键、INSTORE 重复走保留键）。
        assertSqlRejected(processingReceiptSql("RCP0520DUP1", 801L, "ORD-P1", "3", "0"));
        assertSqlRejected(processingReceiptSql("RCP0520DUP2", null, "ORD-LEGACY", "3", "0"));
        assertSqlRejected(cardOrderSql(null, "SC0520DUP1", 801L, "ORD-P1", 697L, null));
        assertSqlRejected(instoreOrderSql(null, "SC0520DUP2", "RT-INST-052", null));
        // remark 可写入且可读回。
        execute("UPDATE sc_supply_chain_order SET remark = '内部备注：该商品无可用手机号，本单未执行每人每周期限购' WHERE id = 101");
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_supply_chain_order
                WHERE id = 101 AND remark = '内部备注：该商品无可用手机号，本单未执行每人每周期限购'
                """)).isEqualTo(1);
        // STANDARD_REQUEST 路由主体键已转全局订单号；无订单历史轮次保留原键仅作审计。
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_supplier_routing_round
                WHERE id = 711 AND routing_subject_key = 'SC0520111'
                """)).isEqualTo(1);
        assertThat(queryCount("""
                SELECT COUNT(*) FROM sc_supplier_routing_round
                WHERE id = 712 AND routing_subject_key = 'ORD-NOORDER'
                """)).isEqualTo(1);
        // 频率覆盖门禁 READY→CLOSED；审计标准旧用户摘要清 NULL；每人限购 user 索引删除、手机号共享索引保留。
        assertThat(queryCount(
                "SELECT COUNT(*) FROM sc_recharge_frequency_migration_gate WHERE state = 'CLOSED'")).isEqualTo(1);
        assertThat(queryCount(
                "SELECT COUNT(*) FROM sc_sensitive_access_audit WHERE buyer_user_digest IS NOT NULL")).isZero();
        assertThat(queryCount(
                "SELECT COUNT(*) FROM sc_fulfillment_asset WHERE asset_no = 'FA0520701'")).isEqualTo(1);
        assertThat(indexExists(ORDER, "idx_sc_order_purchase_limit")).isFalse();
        assertThat(indexExists(ORDER, "idx_sc_order_phone_bind_limit")).isTrue();

        // 11. 已应用后重复执行必须幂等，不再重复改动。
        liquibaseUpdate(1);
        assertThat(columnExists(RECEIPT, "buyer_user_id")).isFalse();
        assertThat(queryCount(
                "SELECT COUNT(*) FROM sc_recharge_frequency_migration_gate WHERE state = 'CLOSED'")).isEqualTo(1);
    }

    /** HALT 预条件失败必须能从异常因果链定位，且 052 未被执行。 */
    private static void assertNextChangesetHalts() {
        Throwable error = catchThrowable(() -> liquibaseUpdate(1));
        assertThat(error).as("预条件冲突必须 HALT").isNotNull();
        assertThat(hasPreconditionFailure(error)).isTrue();
    }

    /** HALT 语义为不执行任何 DDL/DML：旧列仍在、门禁保持 READY，逐场景核对注入行原样保留。 */
    private static void assertHaltKeepsPreState(String sql, int expected) throws SQLException {
        assertThat(queryCount(sql)).isEqualTo(expected);
        assertThat(columnExists(RECEIPT, "buyer_user_id")).isTrue();
        assertThat(columnExists(ORDER, "buyer_user_id")).isTrue();
        assertThat(queryCount(
                "SELECT COUNT(*) FROM sc_recharge_frequency_migration_gate WHERE state = 'READY'")).isEqualTo(1);
    }

    private static boolean hasPreconditionFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof PreconditionFailedException) {
                return true;
            }
        }
        return false;
    }

    /** 场景注入涉及回执、总单两表按 buyer_order_no 清理，保证旧唯一键可恢复。 */
    private static void cleanupScenarioRows(String buyerOrderNoLiteral) throws SQLException {
        execute("DELETE FROM sc_fulfillment_acceptance_receipt WHERE buyer_order_no = " + buyerOrderNoLiteral);
        execute("DELETE FROM sc_supply_chain_order WHERE buyer_order_no = " + buyerOrderNoLiteral);
    }

    /** 模拟旧唯一键缺失/漂移，让非法历史得以落库以验证 HALT 预条件本身。 */
    private static void dropOldBusinessUniqueKeys() throws SQLException {
        execute("ALTER TABLE sc_fulfillment_acceptance_receipt DROP KEY uk_sc_fulfillment_receipt_business");
        execute("ALTER TABLE sc_supply_chain_order DROP KEY uk_sc_order_buyer_business");
    }

    private static void restoreOldBusinessUniqueKeys() throws SQLException {
        execute("""
                ALTER TABLE sc_fulfillment_acceptance_receipt
                    ADD UNIQUE KEY uk_sc_fulfillment_receipt_business (buyer_id, buyer_order_no)
                """);
        execute("""
                ALTER TABLE sc_supply_chain_order
                    ADD UNIQUE KEY uk_sc_order_buyer_business (buyer_id, buyer_order_no)
                """);
    }

    /** 项目归属链预条件核对的采购项目（project 801 属于采购商 1）。 */
    private static void insertBuyerProject() throws SQLException {
        execute("""
                INSERT INTO sc_buyer_project(id, project_code, project_name, buyer_id, catalog_mode, status, version,
                    created_at, updated_at)
                VALUES (801, 'PRJ_052', '052迁移验证项目', 1, 'MANAGED', 10, 0, NOW(3), NOW(3))
                """);
    }

    /** 051 基线门禁置 READY：052 必须重置 CLOSED 且 HALT 时不得被改动。 */
    private static void setGateReady() throws SQLException {
        execute("""
                UPDATE sc_recharge_frequency_migration_gate
                SET state = 'READY', verified_artifact = 'deploy-051-baseline',
                    identity_key_fingerprint = UNHEX(SHA2('gate-identity-key', 256)), verified_at = NOW(3)
                WHERE id = 1
                """);
    }

    /** 051 旧 schema 上已受理的历史回执：project 可空、购买人列为旧 NOT NULL 值。 */
    private static void insertAcceptedReceipt(long id, Long projectId, String buyerOrderNo,
                                               long orderId, String orderNo, String requestNo) throws SQLException {
        execute("""
                INSERT INTO sc_fulfillment_acceptance_receipt (id, receipt_no, buyer_id, project_id, buyer_order_no,
                    buyer_user_id, buyer_sku_code, quantity, target_schema_code, target_digest, invariant_fingerprint,
                    request_fingerprint, listing_version_no, listing_version_id, attempt_no, status,
                    result_order_id, result_order_no, result_fulfillment_request_no, accepted_at,
                    created_at, updated_at)
                VALUES (%d, 'RCP0520%03d', 1, %s, '%s', 'legacy-receipt-user', 'SKU052', 1, 'STD_TARGET',
                    UNHEX(SHA2('target-%d', 256)), UNHEX(SHA2('invariant-%d', 256)), UNHEX(SHA2('request-%d', 256)),
                    3, 851, 1, 10, %d, '%s', '%s', NOW(3), NOW(3), NOW(3))
                """.formatted(id, id % 1000, projectId == null ? "NULL" : projectId, buyerOrderNo,
                id, id, id, orderId, orderNo, requestNo));
    }

    /** 051 旧 schema 上被拒绝的历史回执（REJECTED_RETRYABLE），不关联总单。 */
    private static void insertRejectedReceipt(long id, Long projectId, String buyerOrderNo,
                                               String rejectionCode) throws SQLException {
        execute("""
                INSERT INTO sc_fulfillment_acceptance_receipt (id, receipt_no, buyer_id, project_id, buyer_order_no,
                    buyer_user_id, buyer_sku_code, quantity, target_schema_code, target_digest, invariant_fingerprint,
                    request_fingerprint, listing_version_no, attempt_no, status, rejection_code, rejected_at,
                    created_at, updated_at)
                VALUES (%d, 'RCP0520%03d', 1, %s, '%s', 'legacy-receipt-user', 'SKU052', 1, 'STD_TARGET',
                    UNHEX(SHA2('target-%d', 256)), UNHEX(SHA2('invariant-%d', 256)), UNHEX(SHA2('request-%d', 256)),
                    3, 1, 20, '%s', NOW(3), NOW(3), NOW(3))
                """.formatted(id, id % 1000, projectId == null ? "NULL" : projectId, buyerOrderNo,
                id, id, id, rejectionCode));
    }

    /** 052 后的 PROCESSING 回执插入 SQL：核对新 CHECK 与双唯一键（project/listingVersion/fingerprint 显式传入）。 */
    private static String processingReceiptSql(String receiptNo, Long projectId, String buyerOrderNo,
                                                String listingVersionNo, String fingerprintVersion) {
        return """
                INSERT INTO sc_fulfillment_acceptance_receipt (receipt_no, buyer_id, project_id, buyer_order_no,
                    buyer_sku_code, quantity, target_schema_code, target_digest, invariant_fingerprint,
                    request_fingerprint, listing_version_no, status, fingerprint_version, created_at, updated_at)
                VALUES ('%s', 1, %s, '%s', 'SKU052', 1, 'STD_TARGET',
                    UNHEX(SHA2('t-%s', 256)), UNHEX(SHA2('i-%s', 256)), UNHEX(SHA2('r-%s', 256)),
                    %s, 0, %s, NOW(3), NOW(3))
                """.formatted(receiptNo, projectId == null ? "NULL" : projectId, buyerOrderNo,
                receiptNo, receiptNo, receiptNo, listingVersionNo, fingerprintVersion);
    }

    /** CARD 总单插入 SQL：051 基线带购买人列；052 后购买人须为 NULL（非空用于验证 CHECK 拒绝）。 */
    private static String cardOrderSql(Long id, String orderNo, Long projectId, String buyerOrderNo,
                                        Long receiptId, String buyerUser) {
        return """
                INSERT INTO sc_supply_chain_order (id, order_no, order_type, buyer_id, project_id,
                    buyer_code_snapshot, buyer_name_snapshot, buyer_user_id, buyer_order_no, acceptance_receipt_id,
                    buyer_settlement_amount, currency, platform_sku_id, quantity, request_fingerprint,
                    order_status, aftersale_status, business_stage, detail_type, detail_version,
                    created_at, updated_at)
                VALUES (%s, '%s', 'CARD', 1, %s, 'B052', '052迁移采购商', %s, '%s', %s, 10100, 'CNY', 901, 1,
                    UNHEX(SHA2('f-%s', 256)), 10, 0, 'ACCEPTED', 'FULFILLMENT', 1, NOW(3), NOW(3))
                """.formatted(id == null ? "NULL" : id, orderNo, projectId == null ? "NULL" : projectId,
                buyerUser == null ? "NULL" : "'%s'".formatted(buyerUser), buyerOrderNo,
                receiptId == null ? "NULL" : receiptId, orderNo);
    }

    /** INSTORE 总单插入 SQL：051 基线购买人为真实用户，052 后保留键仍约束 (buyer, buyerOrderNo) 唯一。 */
    private static String instoreOrderSql(Long id, String orderNo, String buyerOrderNo, String buyerUser) {
        return """
                INSERT INTO sc_supply_chain_order (id, order_no, order_type, buyer_id, project_id,
                    buyer_code_snapshot, buyer_name_snapshot, buyer_user_id, buyer_order_no,
                    buyer_settlement_amount, currency, order_status, aftersale_status, business_stage, detail_type,
                    display_supplier_id, request_fingerprint, detail_version, created_at, updated_at)
                VALUES (%s, '%s', 'INSTORE', 1, NULL, 'B052', '052迁移采购商', %s, '%s', 8800, 'CNY',
                    10, 0, 'ACCEPTED', 'INSTORE_CHANNEL', 1, UNHEX(SHA2('f-%s', 256)), 1, NOW(3), NOW(3))
                """.formatted(id == null ? "NULL" : id, orderNo,
                buyerUser == null ? "NULL" : "'%s'".formatted(buyerUser), buyerOrderNo, orderNo);
    }

    /** STANDARD_REQUEST 历史路由轮次：旧主体键=采购商订单号，order 可空表示无候选轮次。 */
    private static void insertRoutingRound(long id, String roundNo, String subjectKey,
                                            Long orderId, int decisionStatus) throws SQLException {
        execute("""
                INSERT INTO sc_supplier_routing_round (id, routing_round_no, buyer_id, routing_subject_type,
                    routing_subject_key, routing_type, order_id, round_sequence, policy_id, policy_version_id,
                    matched_rule_id, routing_input_digest, snapshot_schema_version, fact_snapshot_json,
                    candidate_count, decision_status, decision_reason_code, decision_reason_message,
                    computed_at, created_at)
                VALUES (%d, '%s', 1, 'STANDARD_REQUEST', '%s', 'STANDARD_PRODUCT', %s, 1, %s, %s, %s,
                    UNHEX(SHA2('rr-%s', 256)), 1, '{}', %d, %d, '%s', '052迁移验证轮次', NOW(3), NOW(3))
                """.formatted(id, roundNo, subjectKey, orderId == null ? "NULL" : orderId,
                decisionStatus == 10 ? "1" : "NULL", decisionStatus == 10 ? "1" : "NULL",
                decisionStatus == 10 ? "1" : "NULL", roundNo, decisionStatus == 10 ? 1 : 0,
                decisionStatus, decisionStatus == 10 ? "CANDIDATE_SELECTED" : "NO_CANDIDATE"));
    }

    /** 051 旧 schema 上的标准资产行：购买人列删除后行本身保留。 */
    private static void insertAsset(long id, String assetNo, String legacyUser) throws SQLException {
        execute("""
                INSERT INTO sc_fulfillment_asset (id, asset_no, fulfillment_request_id, b_order_id, operation_id,
                    supplier_id, buyer_id, buyer_user_id, sequence_no, unit_quantity, asset_type, encrypted_payload,
                    masked_value, fingerprint, key_version, lifecycle_status, version, created_at, updated_at)
                VALUES (%d, '%s', 1, 1, 1, 1, 1, '%s', 1, 1, 'VOUCHER_CODE', X'01', '****',
                    UNHEX(SHA2('asset-%s', 256)), 'v1', 20, 0, NOW(3), NOW(3))
                """.formatted(id, assetNo, legacyUser, assetNo));
    }

    /** 051 旧 schema 上的标准资产解密审计行：052 须清空旧购买人用户摘要。 */
    private static void insertAudit(long id, String auditNo) throws SQLException {
        execute("""
                INSERT INTO sc_sensitive_access_audit (id, audit_no, access_type, principal_type, principal_id,
                    buyer_id, buyer_user_digest, resource_type, resource_id, scope_code, result_status, reason_code,
                    trace_id, accessed_at)
                VALUES (%d, '%s', 'ASSET_DECRYPT', 'ADMIN_USER', '1', 1, UNHEX(SHA2('legacy-user-digest', 256)),
                    'FULFILLMENT_ASSET', 701, 'ADMIN_ORDER_VIEW', 10, 'ALLOWED', 'trace-052', NOW(3))
                """.formatted(id, auditNo));
    }

    /** 违反 052 新增 CHECK/唯一键的插入必须以 SQLException 失败。 */
    private static void assertSqlRejected(String sql) {
        Throwable error = catchThrowable(() -> execute(sql));
        assertThat(error).isNotNull().isInstanceOf(SQLException.class);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate(sql)).isNotNegative();
        }
    }

    private static void liquibaseUpdate(int maxCount) throws Exception {
        try (Connection connection = connection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(maxCount, new Contexts(), new LabelExpression());
            }
        }
    }

    private static boolean columnExists(String table, String column) throws SQLException {
        return queryCount("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '%s' AND COLUMN_NAME = '%s'
                """.formatted(table, column)) == 1;
    }

    private static boolean indexExists(String table, String indexName) throws SQLException {
        return queryCount("""
                SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '%s' AND INDEX_NAME = '%s'
                """.formatted(table, indexName)) == 1;
    }

    private static boolean isNullable(String table, String column) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT IS_NULLABLE FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                     """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return "YES".equalsIgnoreCase(resultSet.getString(1));
            }
        }
    }

    private static int queryCount(String sql) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
