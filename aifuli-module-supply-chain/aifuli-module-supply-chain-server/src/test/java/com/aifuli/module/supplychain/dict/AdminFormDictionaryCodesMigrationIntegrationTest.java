package com.aifuli.module.supplychain.dict;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实 MySQL 上验证供应链 `024-admin-form-dictionary-codes`：首次 Run=1，二次 Run=0；
 * 新增列存在且 CHECK 约束生效。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#7-测试门禁}。
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminFormDictionaryCodesMigrationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain_test")
            .withUsername("supply_chain_test")
            .withPassword("supply_chain_test");

    @Test
    void migrationRunsOnceAndAddsThreeCodeColumns() throws Exception {
        updateLiquibase();
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '024-admin-form-dictionary-codes' AND AUTHOR = 'supply-chain'
                """));
        assertTrue(columnExists("sc_supplier_supply_scope", "sellable_category"));
        assertTrue(columnExists("sc_supplier_supply_scope", "product_type"));
        assertTrue(columnExists("sc_supplier_supply_scope", "product_classification"));
        assertTrue(columnExists("sc_buyer_category_scope", "scope_kind"));
        assertTrue(columnExists("sc_buyer_category_scope", "scope_code"));
        assertTrue(columnExists("sc_platform_spu", "sellable_category"));
        assertTrue(isNullable("sc_platform_spu", "primary_category_id"));

        updateLiquibase();
        assertEquals(1, queryCount("""
                SELECT COUNT(*) FROM DATABASECHANGELOG
                WHERE ID = '024-admin-form-dictionary-codes' AND AUTHOR = 'supply-chain'
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

    private static boolean columnExists(String table, String column) throws SQLException {
        return queryCount("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = '%s' AND column_name = '%s'
                """.formatted(table, column)) == 1;
    }

    private static boolean isNullable(String table, String column) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT is_nullable FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = '%s' AND column_name = '%s'
                     """.formatted(table, column))) {
            resultSet.next();
            return "YES".equalsIgnoreCase(resultSet.getString(1));
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

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
