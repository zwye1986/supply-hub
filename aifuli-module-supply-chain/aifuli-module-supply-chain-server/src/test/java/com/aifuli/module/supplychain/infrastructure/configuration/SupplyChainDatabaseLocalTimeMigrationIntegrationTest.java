package com.aifuli.module.supplychain.infrastructure.configuration;

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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StringUtils;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 MySQL 验证管理端兼容变更集之后执行 022 时间语义原地迁移。
 *
 * <p>该测试锁定三个不可变约束：不删除供应链 schema 与 Liquibase 历史、既有 UTC 墙上时间只增加
 * 一次八小时、空值和字段类型不因迁移改变。设计见
 * {@code docs/superpowers/specs/2026-09-04-supply-chain-database-local-time-design.md#4-字段迁移规则}。</p>
 */
class SupplyChainDatabaseLocalTimeMigrationIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain_local_time_migration")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL =
            System.getProperty("supply-chain.local-time-migration-test.jdbc-url");

    private static final LocalDateTime UTC_WALL_TIME =
            LocalDateTime.of(2026, 9, 4, 1, 2, 3, 456_000_000);
    private static final LocalDateTime SHANGHAI_WALL_TIME = UTC_WALL_TIME.plusHours(8);

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
    void upgrades021InPlaceAndDoesNotShiftTimeTwice() throws Exception {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        updateTo021(dataSource);

        jdbc.update("""
                INSERT INTO sc_brand(
                    brand_code, brand_name, status, version, created_at, updated_at)
                VALUES ('BRAND_TIME_MIGRATION', '时间迁移验证品牌', 10, 0, ?, ?)
                """, UTC_WALL_TIME, UTC_WALL_TIME);

        long businessTableCountBefore = businessTableCount(jdbc);
        assertThat(changeSetCount(jdbc)).isEqualTo(21L);
        assertThat(brandCreatedAt(jdbc)).isEqualTo(UTC_WALL_TIME);

        updateRemaining(dataSource);

        // 022 不增表；023 新增开票 2 表；028 新增商品类型 1 表；035 新增通用定价 5 表。
        assertThat(businessTableCount(jdbc)).isEqualTo(businessTableCountBefore + 8);
        assertThat(changeSetCount(jdbc)).isEqualTo(36L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID = '022-database-local-time'
                """, Long.class)).isEqualTo(1L);
        assertThat(brandCreatedAt(jdbc)).isEqualTo(SHANGHAI_WALL_TIME);
        // 022 迁移后 250 列 + 023 开票表 4 列 + 028 商品类型表 2 列 + 035 定价表 11 列 DATETIME(3)
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME LIKE 'sc\\_%'
                  AND DATA_TYPE = 'datetime'
                  AND DATETIME_PRECISION = 3
                  AND COLUMN_COMMENT LIKE '%Asia/Shanghai%'
                """, Long.class)).isEqualTo(267L);

        updateRemaining(dataSource);

        assertThat(changeSetCount(jdbc)).isEqualTo(36L);
        assertThat(brandCreatedAt(jdbc)).isEqualTo(SHANGHAI_WALL_TIME);
    }

    @Test
    void keepsRestartSafeStatementOrderAndContainsNoDestructiveSql() throws Exception {
        String migrationSql = new ClassPathResource(
                "db/changelog/releases/1.0.0/022-database-local-time.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(StringUtils.countOccurrencesOf(migrationSql, "ALTER TABLE")).isEqualTo(250);
        assertThat(StringUtils.countOccurrencesOf(migrationSql, "DATE_ADD(")).isEqualTo(250);
        assertThat(migrationSql.lastIndexOf("ALTER TABLE"))
                .isLessThan(migrationSql.indexOf("UPDATE `"));
        assertThat(migrationSql).doesNotContain("DROP ", "TRUNCATE ", "DELETE FROM");
    }

    private static void updateTo021(DataSource dataSource) throws Exception {
        update(dataSource, 21);
    }

    private static void updateRemaining(DataSource dataSource) throws Exception {
        update(dataSource, null);
    }

    private static void update(DataSource dataSource, Integer changeSetCount) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                if (changeSetCount == null) {
                    liquibase.update(new Contexts(), new LabelExpression());
                } else {
                    liquibase.update(changeSetCount, new Contexts(), new LabelExpression());
                }
            }
        }
    }

    private static long businessTableCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME LIKE 'sc\\_%'
                """, Long.class);
    }

    private static long changeSetCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class);
    }

    private static LocalDateTime brandCreatedAt(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT created_at FROM sc_brand WHERE brand_code = 'BRAND_TIME_MIGRATION'
                """, LocalDateTime.class);
    }

    private static DataSource dataSource() {
        String url = EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
        String username = EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.local-time-migration-test.jdbc-username", "root")
                : MYSQL.getUsername();
        String password = EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.local-time-migration-test.jdbc-password", "")
                : MYSQL.getPassword();
        return new DriverManagerDataSource(url, username, password);
    }
}
