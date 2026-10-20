package com.aifuli.module.supplychain.dict;

import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

/** 真实MySQL验证增量约束及旧数据保持；完整供应链主清单由商品中心集成门禁另行验证。 */
@Testcontainers
class SuggestedSupplyPriceMigrationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("suggested_price_test").withUsername("test").withPassword("test");
    @TempDir Path temporary;

    @Test void newIncrementsAllowPendingAndPreserveLegacyRowsAndDictionaryExtras() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.isDirectory(root.resolve("aifuli-module-system"))) root = root.getParent();
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE sc_platform_sku (id BIGINT PRIMARY KEY, suggested_sale_price BIGINT NOT NULL DEFAULT 0, suggested_sale_price_rule_json JSON NOT NULL)");
            sql.execute("INSERT INTO sc_platform_sku VALUES (1,8900,JSON_OBJECT('mode','FIXED','fixedPriceCents',8900))");
            for (String table : new String[]{"sc_platform_sku_pricing_version", "sc_buyer_sku_pricing_version", "sc_project_sku_pricing_version"}) {
                sql.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, suggested_sale_price_cents BIGINT NOT NULL)");
                sql.execute("INSERT INTO " + table + " VALUES (1,8900)");
            }
            sql.execute("""
                    CREATE TABLE system_dict_data (id BIGINT AUTO_INCREMENT PRIMARY KEY, sort INT,
                      label VARCHAR(100), value VARCHAR(100), dict_type VARCHAR(100), status TINYINT,
                      color_type VARCHAR(100), css_class VARCHAR(100), remark VARCHAR(500), creator VARCHAR(64),
                      create_time DATETIME, updater VARCHAR(64), update_time DATETIME, deleted BIT(1))
                    """);
            sql.execute("INSERT INTO system_dict_data (value,label,dict_type,deleted) VALUES ('SUGGESTED_SALE_PRICE','人工历史项','supply_chain_suggested_sale_price_base',b'0')");
        }
        Files.writeString(temporary.resolve("migration.yaml"), """
                databaseChangeLog:
                  - changeSet:
                      id: 068-sku-suggested-price-state
                      author: supply-chain
                      changes:
                        - sqlFile:
                            path: aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/068-sku-suggested-price-state.sql
                  - changeSet:
                      id: 019-suggested-sale-price-supply-base
                      author: supply-chain
                      changes:
                        - sqlFile:
                            path: aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/releases/1.0.0/019-suggested-sale-price-supply-base.sql
                """);
        for (int iteration = 0; iteration < 2; iteration++) {
            try (Connection connection = connection(); var resources = new CompositeResourceAccessor(
                    new DirectoryResourceAccessor(temporary), new DirectoryResourceAccessor(root))) {
                var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
                try (Liquibase liquibase = new Liquibase("migration.yaml", resources, database)) {
                    liquibase.update(new Contexts(), new LabelExpression());
                }
            }
        }
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            assertThat(value(sql, "SELECT COUNT(*) FROM DATABASECHANGELOG")).isEqualTo(2);
            assertThat(value(sql, "SELECT suggested_sale_price FROM sc_platform_sku WHERE id=1")).isEqualTo(8900);
            assertThat(value(sql, "SELECT COUNT(*) FROM system_dict_data WHERE value='SUPPLY_PRICE' AND label='供应价' AND status=0 AND sort=2")).isEqualTo(1);
            assertThat(value(sql, "SELECT COUNT(*) FROM system_dict_data WHERE value='SUGGESTED_SALE_PRICE' AND label='人工历史项'")).isEqualTo(1);
            for (String table : new String[]{"sc_platform_sku_pricing_version", "sc_buyer_sku_pricing_version", "sc_project_sku_pricing_version"}) {
                assertThat(value(sql, "SELECT suggested_sale_price_cents FROM " + table + " WHERE id=1")).isEqualTo(8900);
                sql.execute("INSERT INTO " + table + " VALUES (2,NULL)");
            }
            sql.execute("""
                    INSERT INTO sc_sku_suggested_price_state
                      (platform_sku_id,rule_digest,calculation_status,unavailable_reason,calculated_at)
                    VALUES (1,'rule','DEPENDENCY_MISSING','EFFECTIVE_SUPPLY_PRICE_MISSING',NOW(3))
                    """);
            assertThatThrownBy(() -> sql.execute("UPDATE sc_sku_suggested_price_state SET effective_price_cents=0 WHERE platform_sku_id=1"))
                    .isInstanceOf(SQLException.class);
            sql.execute("UPDATE sc_sku_suggested_price_state SET calculation_status='READY',effective_price_cents=8900,last_valid_price_cents=8900,unavailable_reason=NULL WHERE platform_sku_id=1");
            sql.execute("UPDATE sc_sku_suggested_price_state SET calculation_status='DEPENDENCY_MISSING',effective_price_cents=NULL,unavailable_reason='EFFECTIVE_SUPPLY_PRICE_MISSING' WHERE platform_sku_id=1");
            assertThat(value(sql, "SELECT last_valid_price_cents FROM sc_sku_suggested_price_state WHERE platform_sku_id=1")).isEqualTo(8900);
        }
    }
    private Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
    private long value(Statement sql, String query) throws SQLException {
        try (var result = sql.executeQuery(query)) { assertThat(result.next()).isTrue(); return result.getLong(1); }
    }
}
