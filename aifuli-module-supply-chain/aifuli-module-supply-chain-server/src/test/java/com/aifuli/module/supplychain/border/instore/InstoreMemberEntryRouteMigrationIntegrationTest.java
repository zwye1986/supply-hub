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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 验证 017→018 入口凭证项目化与撤销状态前向迁移。
 *
 * <p>历史凭证在 017 之前无法证明签发时的 MEMBER_PROFILE 档案版本和 companyCode，
 * ACTIVE/PROCESSING 必须失败关闭转为 REVOKED；绑定缺失或 projectId 不可恢复时，
 * 永久 DDL 之前迁移失败。设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#72-前向迁移与失败关闭}。</p>
 */
class InstoreMemberEntryRouteMigrationIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain_instore_member_entry")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL =
            System.getProperty("supply-chain.instore-member-entry-test.jdbc-url");

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
    void migratesProjectScopedCredentialRouteAndFailsClosed() throws Exception {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        updateTo017(dataSource);
        insertBuyer(jdbc);
        insertProject(jdbc);
        insertBinding(jdbc, "IUB_MEMBER_ENTRY", 101L);
        long bindingId = bindingId(jdbc, "IUB_MEMBER_ENTRY");

        insertCredential(jdbc, "IEC_ACTIVE", (short) 10);
        insertCredential(jdbc, "IEC_PROCESSING", (short) 15);
        insertCredential(jdbc, "IEC_USED", (short) 20);
        insertCredential(jdbc, "IEC_EXPIRED", (short) 30);

        insertCredential(jdbc, "IEC_ORPHAN", (short) 10, 99999L);
        assertThatThrownBy(() -> updateRemaining(dataSource))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(columnCount(jdbc, "sc_instore_entry_credential", "project_id")).isZero();

        jdbc.update("DELETE FROM sc_instore_entry_credential WHERE credential_no = 'IEC_ORPHAN'");
        updateRemaining(dataSource);

        assertRequiredColumn(jdbc, "sc_instore_entry_credential", "project_id");
        assertNullableColumn(jdbc, "sc_instore_entry_credential", "member_profile_version_id");
        assertNullableColumn(jdbc, "sc_instore_entry_credential", "company_code");
        assertNullableColumn(jdbc, "sc_instore_entry_credential", "revoked_at");
        assertNullableColumn(jdbc, "sc_instore_entry_credential", "revoke_reason");

        assertThat(projectIdOf(jdbc, "IEC_ACTIVE")).isEqualTo(101L);
        assertThat(statusOf(jdbc, "IEC_ACTIVE")).isEqualTo((short) 40);
        assertThat(revokeReasonOf(jdbc, "IEC_ACTIVE")).isEqualTo("MIGRATION_UNFROZEN_ROUTE");
        assertThat(revokedAtOf(jdbc, "IEC_ACTIVE")).isNotNull();
        assertThat(processingStartedAtOf(jdbc, "IEC_PROCESSING")).isNull();
        assertThat(statusOf(jdbc, "IEC_PROCESSING")).isEqualTo((short) 40);
        assertThat(revokeReasonOf(jdbc, "IEC_PROCESSING")).isEqualTo("MIGRATION_UNFROZEN_ROUTE");
        assertThat(statusOf(jdbc, "IEC_USED")).isEqualTo((short) 20);
        assertThat(statusOf(jdbc, "IEC_EXPIRED")).isEqualTo((short) 30);
        assertThat(revokeReasonOf(jdbc, "IEC_USED")).isNull();
        assertThat(revokeReasonOf(jdbc, "IEC_EXPIRED")).isNull();

        assertThatThrownBy(() -> insertMigratedCredential(
                jdbc, bindingId, "IEC_NEW_NO_VERSION", (short) 10, null, "RT-COMPANY"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> insertMigratedCredential(
                jdbc, bindingId, "IEC_NEW_NO_COMPANY", (short) 10, 55L, null))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> insertMigratedCredential(
                jdbc, bindingId, "IEC_NEW_NO_REVOKE_FACT", (short) 40, 55L, "RT-COMPANY"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        insertMigratedCredential(jdbc, bindingId, "IEC_NEW_ACTIVE", (short) 10, 55L, "RT-COMPANY");

        long changeSetCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class);
        assertThat(changeSetCount).isEqualTo(51L);
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

    private static void assertNullableColumn(
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
                .containsEntry("IS_NULLABLE", "YES"));
    }

    private static short statusOf(JdbcTemplate jdbc, String credentialNo) {
        return jdbc.queryForObject("""
                SELECT status FROM sc_instore_entry_credential WHERE credential_no = ?
                """, Short.class, credentialNo);
    }

    private static Long projectIdOf(JdbcTemplate jdbc, String credentialNo) {
        return jdbc.queryForObject("""
                SELECT project_id FROM sc_instore_entry_credential WHERE credential_no = ?
                """, Long.class, credentialNo);
    }

    private static String revokeReasonOf(JdbcTemplate jdbc, String credentialNo) {
        return jdbc.queryForObject("""
                SELECT revoke_reason FROM sc_instore_entry_credential WHERE credential_no = ?
                """, String.class, credentialNo);
    }

    private static LocalDateTime revokedAtOf(JdbcTemplate jdbc, String credentialNo) {
        return jdbc.queryForObject("""
                SELECT revoked_at FROM sc_instore_entry_credential WHERE credential_no = ?
                """, LocalDateTime.class, credentialNo);
    }

    private static LocalDateTime processingStartedAtOf(JdbcTemplate jdbc, String credentialNo) {
        return jdbc.queryForObject("""
                SELECT processing_started_at FROM sc_instore_entry_credential WHERE credential_no = ?
                """, LocalDateTime.class, credentialNo);
    }

    private static long bindingId(JdbcTemplate jdbc, String bindingNo) {
        return jdbc.queryForObject("""
                SELECT id FROM sc_instore_channel_user_binding WHERE binding_no = ?
                """, Long.class, bindingNo);
    }

    private static void insertBuyer(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sc_buyer(
                    id, buyer_code, buyer_name, short_name, buyer_type,
                    status, version, created_at, updated_at)
                VALUES (1, 'BUYER_MEMBER_ENTRY', '会员入口采购商', '会员入口', 'SAAS_MALL',
                        10, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """);
    }

    private static void insertProject(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sc_buyer_project(
                    id, project_code, project_name, buyer_id, catalog_mode,
                    status, version, created_at, updated_at)
                VALUES (101, 'INSTORE_MEMBER_ENTRY', '会员入口项目', 1, 'INDEPENDENT',
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
                VALUES (?, 1, ?, 'AIFULI_MALL', 'tenant-member-entry',
                        'opaque-user', 'RT_MART', 10, 0,
                        UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, bindingNo, projectId);
    }

    private static void insertCredential(
            JdbcTemplate jdbc, String credentialNo, short status) {
        insertCredential(jdbc, credentialNo, status, null);
    }

    private static void insertCredential(
            JdbcTemplate jdbc, String credentialNo, short status, Long bindingId) {
        jdbc.update("""
                INSERT INTO sc_instore_entry_credential(
                    credential_no, binding_id, token_digest, purpose, instore_order_id,
                    status, expires_at, processing_started_at, used_at,
                    version, created_at, updated_at)
                VALUES (?, ?, UNHEX(SHA2(?, 256)), 'HOME', NULL,
                        ?, UTC_TIMESTAMP(3) + INTERVAL 10 MINUTE, ?, ?,
                        0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """,
                credentialNo,
                bindingId != null ? bindingId : bindingId(jdbc, "IUB_MEMBER_ENTRY"),
                credentialNo,
                status,
                status == 15 ? LocalDateTime.now(java.time.ZoneOffset.UTC) : null,
                status == 20 ? LocalDateTime.now(java.time.ZoneOffset.UTC) : null);
    }

    private static void insertMigratedCredential(
            JdbcTemplate jdbc, long bindingId, String credentialNo, short status,
            Long memberProfileVersionId, String companyCode) {
        jdbc.update("""
                INSERT INTO sc_instore_entry_credential(
                    credential_no, binding_id, project_id, token_digest, purpose,
                    instore_order_id, status, expires_at,
                    member_profile_version_id, company_code,
                    version, created_at, updated_at)
                VALUES (?, ?, 101, UNHEX(SHA2(?, 256)), 'HOME', NULL, ?,
                        UTC_TIMESTAMP(3) + INTERVAL 10 MINUTE, ?, ?,
                        0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """, credentialNo, bindingId, credentialNo, status,
                memberProfileVersionId, companyCode);
    }

    private static void updateTo017(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(17, new Contexts(), new LabelExpression());
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
                ? System.getProperty("supply-chain.instore-member-entry-test.jdbc-username", "root")
                : MYSQL.getUsername();
        String password = EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.instore-member-entry-test.jdbc-password", "")
                : MYSQL.getPassword();
        return new DriverManagerDataSource(url, username, password);
    }
}
