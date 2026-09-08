package com.aifuli.module.system.service.dict;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 在真实 MySQL 上验证 system 管理端表单字典：首次全量写入、二次幂等。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#7-测试门禁}。
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminFormDictionaryMigrationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("system_test")
            .withUsername("system_test")
            .withPassword("system_test");

    @BeforeAll
    static void createYudaoDictionaryTables() throws SQLException {
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
        }
    }

    @Test
    void migrationWritesNineDictionariesOnlyOnce() throws Exception {
        updateLiquibase();
        assertEquals(9, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type IN (
                    'supply_chain_sellable_category', 'supply_chain_product_type',
                    'supply_chain_product_classification', 'supply_chain_distribution_level',
                    'supply_chain_cooperation_mode', 'supply_chain_distributor_type',
                    'supply_chain_supply_preference', 'supply_chain_buyer_status',
                    'supply_chain_supply_mode')
                  AND deleted = b'0'
                """));
        assertEquals(38, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type IN (
                    'supply_chain_sellable_category', 'supply_chain_product_type',
                    'supply_chain_product_classification', 'supply_chain_distribution_level',
                    'supply_chain_cooperation_mode', 'supply_chain_distributor_type',
                    'supply_chain_supply_preference', 'supply_chain_buyer_status',
                    'supply_chain_supply_mode')
                  AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '002-admin-form-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals("暂停分销", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_buyer_status' AND value = 'SUSPENDED' AND deleted = b'0'
                """));
        assertEquals(5, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type = 'supply_chain_supply_mode' AND deleted = b'0'
                """));
        assertEquals("一件代发", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_supply_mode' AND value = 'DROP_SHIP' AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '003-supply-mode-dictionary' AND AUTHOR = 'supply-chain'
                """));

        updateLiquibase();
        assertEquals(9, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type IN (
                    'supply_chain_sellable_category', 'supply_chain_product_type',
                    'supply_chain_product_classification', 'supply_chain_distribution_level',
                    'supply_chain_cooperation_mode', 'supply_chain_distributor_type',
                    'supply_chain_supply_preference', 'supply_chain_buyer_status',
                    'supply_chain_supply_mode')
                  AND deleted = b'0'
                """));
        assertEquals(38, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type IN (
                    'supply_chain_sellable_category', 'supply_chain_product_type',
                    'supply_chain_product_classification', 'supply_chain_distribution_level',
                    'supply_chain_cooperation_mode', 'supply_chain_distributor_type',
                    'supply_chain_supply_preference', 'supply_chain_buyer_status',
                    'supply_chain_supply_mode')
                  AND deleted = b'0'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '002-admin-form-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '003-supply-mode-dictionary' AND AUTHOR = 'supply-chain'
                """));
    }

    private static void updateLiquibase() throws Exception {
        try (Connection connection = connection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static int queryCount(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String queryText(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
