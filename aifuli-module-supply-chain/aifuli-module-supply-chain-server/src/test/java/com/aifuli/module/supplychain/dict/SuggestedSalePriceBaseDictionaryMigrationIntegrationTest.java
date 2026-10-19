package com.aifuli.module.supplychain.dict;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL 验证 system 基准字典迁移与热更的幂等性和人工数据保留。
 * 仅装载本次 system 变更集；主清单注册由 SuggestedSalePriceBaseDictionaryLiquibaseTest 校验。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#531-销售建议价基准来源字典与历史规则兼容2026-10-18}。
 */
@Testcontainers
class SuggestedSalePriceBaseDictionaryMigrationIntegrationTest {
    private static final String CHANGESET = "018-suggested-sale-price-base-dictionary";
    private static final String DICT = "supply_chain_suggested_sale_price_base";
    private static final Path ROOT = findRepoRoot();
    @TempDir
    static Path changelogDir;
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("system_price_base_test").withUsername("test").withPassword("test");

    @BeforeAll
    static void prepareDictionaryTables() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE system_dict_type (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      name VARCHAR(100) NOT NULL DEFAULT '',
                      type VARCHAR(100) NOT NULL DEFAULT '',
                      status TINYINT NOT NULL DEFAULT 0,
                      remark VARCHAR(500) NULL,
                      creator VARCHAR(64) NULL DEFAULT '',
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updater VARCHAR(64) NULL DEFAULT '',
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      deleted BIT(1) NOT NULL DEFAULT b'0',
                      deleted_time DATETIME NULL DEFAULT NULL,
                      PRIMARY KEY (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            statement.execute("""
                    CREATE TABLE system_dict_data (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      sort INT NOT NULL DEFAULT 0,
                      label VARCHAR(100) NOT NULL DEFAULT '',
                      value VARCHAR(100) NOT NULL DEFAULT '',
                      dict_type VARCHAR(100) NOT NULL DEFAULT '',
                      status TINYINT NOT NULL DEFAULT 0,
                      color_type VARCHAR(100) NULL DEFAULT '',
                      css_class VARCHAR(100) NULL DEFAULT '',
                      remark VARCHAR(500) NULL,
                      creator VARCHAR(64) NULL DEFAULT '',
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updater VARCHAR(64) NULL DEFAULT '',
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      deleted BIT(1) NOT NULL DEFAULT b'0',
                      PRIMARY KEY (id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            statement.execute("""
                    INSERT INTO system_dict_data (sort, label, value, dict_type, status, remark)
                    VALUES (99, '人工历史项', 'SUGGESTED_SALE_PRICE', 'supply_chain_suggested_sale_price_base', 0, '保留人工配置')
                    """);
        }
        Files.writeString(changelogDir.resolve("base-dict.yaml"), """
                databaseChangeLog:
                  - changeSet:
                      id: 018-suggested-sale-price-base-dictionary
                      author: supply-chain
                      changes:
                        - sqlFile:
                            path: aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/releases/1.0.0/018-suggested-sale-price-base-dictionary.sql
                            encoding: UTF-8
                            splitStatements: true
                """);
    }

    @Test
    void migrationAndHotfixCanRepeatWithoutDuplicatingOrDeletingManualData() throws Exception {
        migrate();
        assertDictionary();
        migrate();
        assertDictionary();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE system_dict_data SET label='旧市场价', status=1 WHERE dict_type='" + DICT + "' AND value='MARKET_PRICE'");
            statement.execute("UPDATE system_dict_type SET status=1 WHERE type='" + DICT + "'");
        }
        for (int run = 0; run < 2; run++) {
            try (Connection connection = connection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(ROOT.resolve(
                        "script/deploy/aifuli-test/sql/system-supply-chain-suggested-sale-price-base-dict.sql")));
            }
            assertDictionary();
        }
    }

    private static void assertDictionary() throws Exception {
        assertCount("SELECT COUNT(*) FROM system_dict_type WHERE type='" + DICT + "' AND status=0 AND deleted=b'0'", 1);
        assertCount("SELECT COUNT(*) FROM system_dict_data WHERE dict_type='" + DICT + "' AND deleted=b'0'", 2);
        assertCount("SELECT COUNT(*) FROM system_dict_data WHERE dict_type='" + DICT + "' AND value='MARKET_PRICE' AND label='市场价' AND status=0 AND sort=1 AND deleted=b'0'", 1);
        assertCount("SELECT COUNT(*) FROM system_dict_data WHERE dict_type='" + DICT + "' AND value='SUGGESTED_SALE_PRICE' AND remark='保留人工配置' AND label='人工历史项'", 1);
        assertCount("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID='" + CHANGESET + "' AND AUTHOR='supply-chain'", 1);
    }

    private static void assertCount(String sql, int expected) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).as(sql).isEqualTo(expected);
        }
    }

    private static void migrate() throws Exception {
        try (Connection connection = connection();
             var resources = new CompositeResourceAccessor(new DirectoryResourceAccessor(changelogDir),
                     new DirectoryResourceAccessor(ROOT))) {
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("base-dict.yaml", resources, database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Path findRepoRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("aifuli-module-system"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("找不到仓库根目录");
        return path;
    }
}
