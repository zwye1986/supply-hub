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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 验证 INSTORE 项目业务身份和收银台档案版本快照。
 *
 * <p>同一 SaaS 外部租户可以服务多个采购项目，但稳定会员绑定必须按
 * {@code (projectId,buyerUserId,channelCode)} 隔离；订单必须固化项目和收银台档案版本，
 * 供回调、查询及 Timer 按原业务号恢复路由。设计见
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#51-saas-商城收银台编排}。</p>
 */
class InstoreProjectCashierRouteMigrationIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain_instore_project_cashier")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL =
            System.getProperty("supply-chain.instore-project-route-test.jdbc-url");

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
    void persistsProjectScopedBindingAndCashierProfileSnapshot() throws Exception {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        updateTo016(dataSource);
        insertBuyer(jdbc);
        insertLegacyBinding(jdbc);

        assertThatThrownBy(() -> updateRemaining(dataSource))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(columnCount(jdbc, "sc_instore_channel_user_binding", "project_id")).isZero();

        jdbc.update("DELETE FROM sc_instore_channel_user_binding WHERE binding_no = 'IUB_LEGACY'");
        updateRemaining(dataSource);

        assertRequiredColumn(jdbc, "sc_instore_channel_user_binding", "project_id");
        assertRequiredColumn(jdbc, "sc_instore_channel_order", "project_id");
        assertRequiredColumn(jdbc, "sc_instore_channel_order", "cashier_profile_version_id");

        insertProjects(jdbc);
        insertBinding(jdbc, "IUB_P1", 101L);
        insertBinding(jdbc, "IUB_P2", 102L);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM sc_instore_channel_user_binding
                WHERE source_system = 'AIFULI_MALL'
                  AND external_tenant_id = 'tenant-shared'
                  AND buyer_user_id = 'opaque-user'
                  AND channel_code = 'RT_MART'
                """, Long.class)).isEqualTo(2L);

        assertThatThrownBy(() -> insertBinding(jdbc, "IUB_P1_DUP", 101L))
                .isInstanceOf(DuplicateKeyException.class);

        // updateRemaining 已应用到最新 029 个 changeSet，重跑不再新增。
        long changeSetCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class);
        assertThat(changeSetCount).isEqualTo(29L);
        updateRemaining(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class)).isEqualTo(changeSetCount);
    }

    private static long columnCount(
            JdbcTemplate jdbc, String tableName, String columnName) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Long.class, tableName, columnName);
    }

    private static void assertRequiredColumn(
            JdbcTemplate jdbc, String tableName, String columnName) {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME, IS_NULLABLE
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, tableName, columnName);
        assertThat(columns).singleElement().satisfies(column -> assertThat(column)
                    .containsEntry("COLUMN_NAME", columnName)
                    .containsEntry("IS_NULLABLE", "NO"));
    }

    private static void insertBuyer(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sc_buyer(
                    id, buyer_code, buyer_name, short_name, buyer_type,
                    status, version, created_at, updated_at)
                VALUES (1, 'BUYER_INSTORE_ROUTE', '到店购项目采购商', '到店购采购商', 'SAAS_MALL',
                        10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
    }

    private static void insertLegacyBinding(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sc_instore_channel_user_binding(
                    binding_no, buyer_id, source_system, external_tenant_id,
                    buyer_user_id, channel_code, status, version,
                    created_at, updated_at)
                VALUES ('IUB_LEGACY', 1, 'AIFULI_MALL', 'tenant-shared',
                        'opaque-user', 'RT_MART', 10, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
    }

    private static void insertProjects(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sc_buyer_project(
                    id, project_code, project_name, buyer_id, catalog_mode,
                    status, version, created_at, updated_at)
                VALUES
                    (101, 'INSTORE_P1', '到店购项目一', 1, 'INDEPENDENT',
                     10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
                    (102, 'INSTORE_P2', '到店购项目二', 1, 'INDEPENDENT',
                     10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
    }

    private static void insertBinding(
            JdbcTemplate jdbc, String bindingNo, long projectId) {
        jdbc.update("""
                INSERT INTO sc_instore_channel_user_binding(
                    binding_no, buyer_id, project_id, source_system,
                    external_tenant_id, buyer_user_id, channel_code,
                    status, version, created_at, updated_at)
                VALUES (?, 1, ?, 'AIFULI_MALL', 'tenant-shared',
                        'opaque-user', 'RT_MART', 10, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bindingNo, projectId);
    }

    private static void updateTo016(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(16, new Contexts(), new LabelExpression());
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

    private static DataSource dataSource() {
        String url = EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
        String username = EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.instore-project-route-test.jdbc-username", "root")
                : MYSQL.getUsername();
        String password = EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.instore-project-route-test.jdbc-password", "")
                : MYSQL.getPassword();
        return new DriverManagerDataSource(url, username, password);
    }
}
