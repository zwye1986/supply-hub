package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

/** 059升级仅开放来源NULL，真实MySQL验证历史来源、索引、价格与重放不变。
 * 设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#4-增量数据契约。
 */
@Testcontainers(disabledWithoutDocker = true)
class StandardProjectDirectOrderQuoteSourceMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");

    @Test
    void upgradesExistingRpcSourcesAndAllowsTrustedNullSourcesIdempotently() throws Exception {
        migrate(60);
        assertThat(nullableSourceCount()).isZero();
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO sc_standard_pricing_quote(quote_token,source_system,external_tenant_id,
                    buyer_id,project_id,listing_id,platform_sku_id,platform_sku_code,project_pricing_version_id,
                    buyer_order_no,quantity,distribution_unit_price_cents,sales_unit_price_cents,product_subtotal_cents,
                    service_fee_cents,user_payable_amount_cents,tax_metadata_json,status,created_at)
                    VALUES ('legacy-source','AIFULI_MALL','old-tenant',1,2,3,4,'SKU_A',9,'ORD-RPC',1,
                    9800,10000,10000,100,10100,'{}','ISSUED',NOW(3))
                    """);
        }
        migrate(1);
        assertThat(nullableSourceCount()).isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM sc_standard_pricing_quote WHERE source_system='AIFULI_MALL' AND external_tenant_id='old-tenant' AND user_payable_amount_cents=10100")).isEqualTo(1L);
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO sc_standard_pricing_quote(quote_token,source_system,external_tenant_id,
                    buyer_id,project_id,listing_id,platform_sku_id,platform_sku_code,project_pricing_version_id,
                    buyer_order_no,quantity,distribution_unit_price_cents,sales_unit_price_cents,product_subtotal_cents,
                    service_fee_cents,user_payable_amount_cents,tax_metadata_json,status,created_at)
                    VALUES ('trusted-source',NULL,NULL,1,2,3,4,'SKU_A',9,'ORD-HTTP',1,
                    9800,10000,10000,100,10100,'{}','ISSUED',NOW(3))
                    """);
        }
        migrate(1);
        assertThat(count("SELECT COUNT(*) FROM DATABASECHANGELOG")).isEqualTo(61L);
        assertThat(count("SELECT COUNT(*) FROM sc_standard_pricing_quote")).isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sc_standard_pricing_quote' AND INDEX_NAME='uk_sc_standard_quote_project_order'")).isEqualTo(2L);
        try (var connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT COLUMN_NAME,COLUMN_COMMENT FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sc_standard_pricing_quote'
                  AND COLUMN_NAME IN ('source_system','external_tenant_id') ORDER BY COLUMN_NAME
                """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(2)).isEqualTo("内部RPC来源系统外部租户标识；项目直接下单首次冻结为空");
            assertThat(result.next()).isTrue();
            assertThat(result.getString(2)).isEqualTo("内部RPC调用来源系统编码；项目直接下单首次冻结为空");
        }
    }
    private static long nullableSourceCount() throws Exception {
        return count("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sc_standard_pricing_quote' AND COLUMN_NAME IN ('source_system','external_tenant_id') AND IS_NULLABLE='YES'");
    }
    private static long count(String sql) throws Exception {
        try (var connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next(); return result.getLong(1);
        }
    }
    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
    private static void migrate(int count) throws Exception {
        try (var connection = connection()) {
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var migration = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database)) {
                migration.update(count, new Contexts(), new LabelExpression());
            }
        }
    }
}
