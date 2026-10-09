package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 的增量迁移、恢复字段与重放门禁，不连接业务库。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#41-追加迁移。
 */
class HuashengOperationPersistenceIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("huasheng_operation").withUsername("supply_chain").withPassword("supply_chain");
    private static final String EXTERNAL_URL = System.getProperty("supply-chain.test.jdbc-url");

    @BeforeAll
    static void start() {
        if (EXTERNAL_URL == null) MYSQL.start();
    }

    @AfterAll
    static void stop() {
        if (EXTERNAL_URL == null && MYSQL.isRunning()) MYSQL.stop();
    }

    @Test
    void migratesRecoveryFieldsEnforcesChecksAndReplaysWithoutRepeatingDdl() throws Exception {
        Map<String, String> firstHistory;
        Map<String, Column> firstColumns;
        try (var context = context(); var connection = context.getBean("supplyChainDataSource", DataSource.class)
                .getConnection()) {
            firstColumns = columns(connection);
            assertThat(firstColumns).containsKeys("checkpoint_key_version", "execution_generation",
                    "execution_lease_until", "recovery_count", "recovery_deadline_at", "business_send_possible_at");
            assertThat(firstColumns.get("checkpoint_key_version"))
                    .isEqualTo(new Column("varchar(64)", null, "YES", "恢复检查点信封加密密钥版本"));
            assertThat(firstColumns.get("execution_generation"))
                    .isEqualTo(new Column("bigint", "0", "NO", "履约操作认领与恢复围栏代次"));
            assertThat(firstColumns.get("recovery_count"))
                    .isEqualTo(new Column("int", "0", "NO", "本操作已认领的原单查单恢复次数"));
            assertThat(firstColumns.get("execution_lease_until"))
                    .isEqualTo(new Column("datetime(3)", null, "YES", "当前履约操作认领到期时间，Asia/Shanghai"));
            assertThat(firstColumns.get("recovery_deadline_at"))
                    .isEqualTo(new Column("datetime(3)", null, "YES", "自动恢复截止时间，Asia/Shanghai"));
            assertThat(firstColumns.get("business_send_possible_at"))
                    .isEqualTo(new Column("datetime(3)", null, "YES", "本操作首次可能发送业务请求的时间，Asia/Shanghai"));
            assertThat(FulfillmentOperationDO.class.getDeclaredField("checkpointKeyVersion").getType())
                    .isEqualTo(String.class);
            try (var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("""
                        SELECT COLUMN_TYPE, COLUMN_COMMENT FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sc_inbox_event'
                        AND COLUMN_NAME = 'payload_digest'
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("binary(32)");
                    assertThat(result.getString(2)).isEqualTo("收到的事件载荷SHA-256族摘要，华昇使用HMAC-SHA256");
                }
                // 不插业务聚合；无物理外键允许独立验证技术列和数据库 CHECK。
                statement.executeUpdate("""
                        INSERT INTO sc_fulfillment_operation (operation_no, b_order_id, operation_kind,
                          adapter_step_code, sequence_no, idempotency_key, config_digest, status, created_at, updated_at)
                        VALUES ('HS_PERSIST_IT', 1, 'FULFILL', 'PLACE_ORDER', 1, 'HS_PERSIST_IT_KEY',
                          UNHEX(REPEAT('00', 32)), 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """);
                try (var result = statement.executeQuery("SELECT execution_generation, recovery_count, "
                        + "checkpoint_key_version FROM sc_fulfillment_operation WHERE operation_no='HS_PERSIST_IT'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                    assertThat(result.getInt(2)).isZero();
                    assertThat(result.getString(3)).isNull();
                }
                for (String field : new String[]{"execution_generation", "recovery_count"}) {
                    assertThatThrownBy(() -> statement.executeUpdate("UPDATE sc_fulfillment_operation SET "
                            + field + "=-1 WHERE operation_no='HS_PERSIST_IT'"))
                            .isInstanceOf(SQLException.class).hasMessageContaining("ck_sc_operation_huasheng_recovery");
                }
                String keyVersion = "k".repeat(64);
                try (var update = connection.prepareStatement("""
                        UPDATE sc_fulfillment_operation SET checkpoint_key_version=?, execution_generation=7,
                          recovery_count=2, execution_lease_until='2026-10-09 12:00:00.123',
                          recovery_deadline_at='2026-10-09 12:30:00.123',
                          business_send_possible_at='2026-10-09 11:59:00.123'
                        WHERE operation_no='HS_PERSIST_IT'
                        """)) {
                    update.setString(1, keyVersion);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }
                try (var result = statement.executeQuery("""
                        SELECT checkpoint_key_version, execution_generation, recovery_count,
                          execution_lease_until, recovery_deadline_at, business_send_possible_at
                        FROM sc_fulfillment_operation WHERE operation_no='HS_PERSIST_IT'
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(keyVersion);
                    assertThat(result.getLong(2)).isEqualTo(7);
                    assertThat(result.getInt(3)).isEqualTo(2);
                    assertThat(result.getTimestamp(4).getNanos()).isEqualTo(123_000_000);
                    assertThat(result.getTimestamp(5).getNanos()).isEqualTo(123_000_000);
                    assertThat(result.getTimestamp(6).getNanos()).isEqualTo(123_000_000);
                }
                try (var result = statement.executeQuery("""
                        SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_index
                        FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sc_fulfillment_operation'
                        GROUP BY INDEX_NAME HAVING columns_in_index='status,next_recover_at,id'
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("idx_sc_fulfillment_operation_recover");
                    assertThat(result.next()).isFalse();
                }
            }
            var mapper = context.getBean(FulfillmentOperationMapper.class);
            var operation = mapper.selectOne(new LambdaQueryWrapper<FulfillmentOperationDO>()
                    .eq(FulfillmentOperationDO::getOperationNo, "HS_PERSIST_IT"));
            assertThat(operation.getCheckpointKeyVersion()).isEqualTo("k".repeat(64));
            assertThat(operation.getExecutionGeneration()).isEqualTo(7);
            assertThat(operation.getRecoveryCount()).isEqualTo(2);
            assertThat(operation.getExecutionLeaseUntil()).isEqualTo("2026-10-09T12:00:00.123");
            assertThat(operation.getRecoveryDeadlineAt()).isEqualTo("2026-10-09T12:30:00.123");
            assertThat(operation.getBusinessSendPossibleAt()).isEqualTo("2026-10-09T11:59:00.123");
            firstHistory = history(connection);
            assertThat(firstHistory).containsKey("062-huasheng-operation-recovery");
        }
        try (var context = context(); var connection = context.getBean("supplyChainDataSource", DataSource.class)
                .getConnection()) {
            assertThat(history(connection)).isEqualTo(firstHistory);
            assertThat(columns(connection)).isEqualTo(firstColumns);
        }
    }

    private ConfigurableApplicationContext context() {
        return new SpringApplicationBuilder(SupplyChainDataSourceConfiguration.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", EXTERNAL_URL == null ? MYSQL.getJdbcUrl() : EXTERNAL_URL,
                        "aifuli.supply-chain.datasource.username", EXTERNAL_URL == null ? MYSQL.getUsername()
                                : System.getProperty("supply-chain.test.jdbc-username", "root"),
                        "aifuli.supply-chain.datasource.password", EXTERNAL_URL == null ? MYSQL.getPassword()
                                : System.getProperty("supply-chain.test.jdbc-password", ""),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"))
                .run();
    }

    private Map<String, Column> columns(Connection connection) throws SQLException {
        Map<String, Column> columns = new LinkedHashMap<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT, IS_NULLABLE, COLUMN_COMMENT
                FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                  AND TABLE_NAME='sc_fulfillment_operation' ORDER BY ORDINAL_POSITION
                """)) {
            while (result.next()) columns.put(result.getString(1), new Column(result.getString(2),
                    result.getString(3), result.getString(4), result.getString(5)));
        }
        return columns;
    }

    private Map<String, String> history(Connection connection) throws SQLException {
        Map<String, String> history = new LinkedHashMap<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT ID, CONCAT(MD5SUM, '|', DATEEXECUTED, '|', ORDEREXECUTED)
                FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED
                """)) {
            while (result.next()) assertThat(history.put(result.getString(1), result.getString(2))).isNull();
        }
        return history;
    }

    private record Column(String type, String defaultValue, String nullable, String comment) { }
}
