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
 * 在真实 MySQL 上验证 system Liquibase 字典变更集及“已有人工数据”兼容路径。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#521-新增供应商数据字典}。
 */
@Testcontainers(disabledWithoutDocker = true)
class SupplyChainSupplierDictionaryMigrationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("system_test")
            .withUsername("system_test")
            .withPassword("system_test");

    @BeforeAll
    static void createYudaoDictionaryTablesAndExistingData() throws SQLException {
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
                    INSERT INTO system_dict_type
                        (name, type, status, remark, creator, updater, deleted)
                    VALUES ('旧配送区域', 'supply_chain_delivery_region_type', 1, '旧数据', 'manual', 'manual', b'0')
                    """);
            statement.execute("""
                    INSERT INTO system_dict_data
                        (sort, label, value, dict_type, status, color_type, css_class, remark,
                         creator, updater, deleted)
                    VALUES (99, '旧全国', 'NATIONWIDE', 'supply_chain_delivery_region_type', 1,
                            'danger', 'legacy', '旧数据', 'manual', 'manual', b'0')
                    """);
        }
    }

    @Test
    void migrationCreatesAndSynchronizesDictionariesOnlyOnce() throws Exception {
        // 001 仅管供应商表单 6 类；002 另增 8 类，断言须按 type 精确圈定，避免 LIKE 'supply_chain_%' 误计。
        String supplierTypes = """
                'supply_chain_delivery_region_type', 'supply_chain_shipping_sla_code',
                'supply_chain_aftersales_supported', 'supply_chain_settlement_method',
                'supply_chain_integration_mode', 'supply_chain_supplier_status'
                """;
        updateLiquibase();
        assertEquals(6, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type IN (%s) AND deleted = b'0'
                """.formatted(supplierTypes)));
        assertEquals(20, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type IN (%s) AND deleted = b'0'
                """.formatted(supplierTypes)));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '001-supplier-form-dictionaries' AND AUTHOR = 'supply-chain'
                """));
        assertEquals("供应商配送区域", queryText("""
                SELECT name FROM system_dict_type
                WHERE type = 'supply_chain_delivery_region_type' AND deleted = b'0'
                """));
        assertEquals("全国", queryText("""
                SELECT label FROM system_dict_data
                WHERE dict_type = 'supply_chain_delivery_region_type'
                  AND value = 'NATIONWIDE' AND deleted = b'0'
                """));

        updateLiquibase();
        assertEquals(6, queryCount("""
                SELECT COUNT(*) FROM system_dict_type
                WHERE type IN (%s) AND deleted = b'0'
                """.formatted(supplierTypes)));
        assertEquals(20, queryCount("""
                SELECT COUNT(*) FROM system_dict_data
                WHERE dict_type IN (%s) AND deleted = b'0'
                """.formatted(supplierTypes)));
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '001-supplier-form-dictionaries' AND AUTHOR = 'supply-chain'
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
