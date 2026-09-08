package com.aifuli.module.supplychain.border.instore;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 验证 INSTORE 013→014 前向升级的不变量和失败关闭语义。
 *
 * <p>本测试使用独占空库，禁止与其它集成测试共用数据库。设计见
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构liquibase-013-instore-channelsql--014-instore-production-invariantssql}。</p>
 */
class InstoreProductionInvariantsMigrationIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain_migration")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL =
            System.getProperty("supply-chain.migration-test.jdbc-url");

    @BeforeAll
    static void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void failsClosedThenBackfillsAndEnforcesPaymentSatelliteUniqueness() throws Exception {
        DataSource dataSource = dataSource();
        updateTo013(dataSource);
        insertBuyerTenantAndLegacyBinding(dataSource, 1L, "AIFULI_MALL", "tenant-a");
        insertCurrentTenant(dataSource, 1L, "PARTNER_MALL", "tenant-b", 2L);

        assertThatThrownBy(() -> applyNextChangeset(dataSource))
                .hasRootCauseInstanceOf(SQLException.class);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'sc_instore_channel_user_binding'
                  AND COLUMN_NAME = 'source_system'
                """, Integer.class)).isZero();
        jdbc.update("""
                UPDATE sc_buyer_tenant_binding
                SET current_flag = NULL, status = 20,
                    valid_to = DATE_ADD(valid_from, INTERVAL 1 SECOND),
                    updated_at = UTC_TIMESTAMP(3)
                WHERE id = 2
                """);

        applyNextChangeset(dataSource);

        assertThat(jdbc.queryForMap("""
                SELECT source_system, external_tenant_id
                FROM sc_instore_channel_user_binding
                WHERE binding_no = 'IUB_LEGACY'
                """))
                .containsEntry("source_system", "AIFULI_MALL")
                .containsEntry("external_tenant_id", "tenant-a");

        // 017 起只要存在历史 INSTORE 绑定/订单数据就在永久 DDL 前失败关闭，要求发布流程
        // 先完成显式项目归属迁移（docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#9-订单用户与幂等）；
        // 本测试只验证 014 的租户回填与支付卫星 1:1 语义，验证完回填后清理该遗留行再升级到最新。
        jdbc.update("DELETE FROM sc_instore_channel_user_binding WHERE binding_no = 'IUB_LEGACY'");

        updateRemaining(dataSource);

        insertPaymentFlow(jdbc, "IPF_A", 99L, "trade-a", "out-a", 1);
        assertThatThrownBy(() -> insertPaymentFlow(
                jdbc, "IPF_B", 99L, "trade-b", "out-b", 2))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static void updateTo013(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(13, new Contexts(), new LabelExpression());
            }
        }
    }

    /**
     * 仅应用下一个变更集（此处即 014）。注意 {@code liquibase.update(int)} 的入参是
     * 本次调用最多应用的变更集数量而非目标序号：传 14 会从 013 一路跑到 017，
     * 在遗留绑定数据上触发 017 的失败关闭，无法单独验证 014 的回填语义。
     */
    private static void applyNextChangeset(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(1, new Contexts(), new LabelExpression());
            }
        }
    }

    private static void updateRemaining(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static void insertBuyerTenantAndLegacyBinding(
            DataSource dataSource, long buyerId,
            String sourceSystem, String externalTenantId) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO sc_buyer(
                    id, buyer_code, buyer_name, short_name, buyer_type,
                    status, version, created_at, updated_at)
                VALUES (?, 'BUYER_MIGRATION', '迁移采购商', '迁移采购商', 'SAAS_MALL',
                        10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, buyerId);
        insertCurrentTenant(dataSource, buyerId, sourceSystem, externalTenantId, 1L);
        jdbc.update("""
                INSERT INTO sc_instore_channel_user_binding(
                    binding_no, buyer_id, buyer_user_id, channel_code,
                    channel_user_id, token, token_status, token_expires_at,
                    status, version, created_at, updated_at)
                VALUES ('IUB_LEGACY', ?, 'opaque-user', 'RT_MART',
                        'legacy-anonymous', 'legacy-plaintext-token', 10,
                        DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 10 MINUTE),
                        10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, buyerId);
    }

    private static void insertCurrentTenant(
            DataSource dataSource, long buyerId,
            String sourceSystem, String externalTenantId, long bindingId) {
        new JdbcTemplate(dataSource).update("""
                INSERT INTO sc_buyer_tenant_binding(
                    id, buyer_id, source_system, external_tenant_id,
                    binding_version, valid_from, valid_to, current_flag,
                    status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, UTC_TIMESTAMP(3), NULL, 1,
                        10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bindingId, buyerId, sourceSystem, externalTenantId);
    }

    private static void insertPaymentFlow(
            JdbcTemplate jdbc, String flowNo, long instoreOrderId,
            String tradeId, String outTradeNo, int sequence) {
        jdbc.update("""
                INSERT INTO sc_instore_payment_flow(
                    payment_flow_no, instore_order_id, order_id, flow_sequence,
                    channel_code, trade_id, user_code, pay_amount, order_type,
                    pay_status, out_trade_no, request_fingerprint, version,
                    created_at, updated_at)
                VALUES (?, ?, 100, ?, 'RT_MART', ?, 'opaque-user', 1000, 1,
                        0, ?, ?, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, flowNo, instoreOrderId, sequence,
                tradeId, outTradeNo, new byte[32]);
    }

    private static DataSource dataSource() {
        String url = EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
        String username = EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.migration-test.jdbc-username", "root")
                : MYSQL.getUsername();
        String password = EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.migration-test.jdbc-password", "")
                : MYSQL.getPassword();
        return new DriverManagerDataSource(url, username, password);
    }
}
