package com.aifuli.module.supplychain.infrastructure.configuration;

import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentAssetStatus;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplyChainLiquibaseIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL =
            System.getProperty("supply-chain.test.jdbc-url");

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
    void migratesAnEmptyDatabaseAndIsIdempotentOnSecondStartup() throws SQLException {
        DatabaseSnapshot firstStartup;
        try (ConfigurableApplicationContext firstContext = applicationContext()) {
            firstStartup = snapshot(firstContext.getBean("supplyChainDataSource", DataSource.class));
        }

        assertThat(firstStartup.tableNames())
                .contains("sc_buyer", "sc_supply_chain_order", "sc_timer_task",
                        "sc_sensitive_access_audit", "sc_instore_entry_credential",
                        "sc_buyer_project", "sc_project_capability",
                        "sc_project_application", "sc_project_app_credential_version",
                        "sc_project_auth_nonce",                         "sc_supplier_contact", "sc_supplier_delivery_region",
                        "sc_supplier_supply_scope",
                        "sc_supplier_supply_scope_mode", "sc_supplier_integration_mode",
                        "sc_supplier_file_ref", "sc_buyer_contact", "sc_buyer_product",
                        "sc_project_listing", "sc_platform_settlement_price_version",
                        "sc_platform_sku_pricing", "sc_platform_sku_pricing_version",
                        "sc_buyer_sku_pricing", "sc_buyer_sku_pricing_version",
                        "sc_project_sku_pricing", "sc_project_sku_pricing_version",
                        "sc_standard_pricing_quote",
                        "sc_supplier_settlement_account", "sc_supplier_settlement_ledger",
                        "sc_supplier_invoice", "sc_buyer_invoice", "sc_product_type")
                .doesNotContain("sc_inventory_asset", "sc_supply_reservation");
        assertThat(firstStartup.columnNames())
                .contains("sc_instore_channel_user_binding.source_system",
                        "sc_instore_channel_user_binding.external_tenant_id",
                        "sc_instore_channel_user_binding.project_id",
                        "sc_instore_channel_order.source_system",
                        "sc_instore_channel_order.external_tenant_id",
                        "sc_instore_channel_order.project_id",
                        "sc_instore_channel_order.cashier_profile_version_id",
                        "sc_instore_entry_credential.project_id",
                        "sc_instore_entry_credential.member_profile_version_id",
                        "sc_instore_entry_credential.company_code",
                        "sc_instore_entry_credential.revoked_at",
                        "sc_instore_entry_credential.revoke_reason",
                        "sc_buyer_integration_profile.project_id",
                        "sc_buyer_integration_capability.source_system",
                        "sc_buyer_integration_capability.external_tenant_id",
                        "sc_outbox_event.version",
                        "sc_supplier.credit_code",
                        "sc_supplier_offer.supply_scope_id",
                        "sc_buyer.parent_buyer_id",
                        "sc_buyer_project.settlement_party_type",
                        "sc_supplier_supply_scope.sellable_category",
                        "sc_platform_spu.product_classification",
                        "sc_platform_spu.secondary_category_ids",
                        "sc_platform_spu.slider_image_urls",
                        "sc_platform_spu.delivery_type",
                        "sc_platform_spu.min_quantity",
                        "sc_platform_sku.suggested_sale_price",
                        "sc_platform_sku.suggested_sale_price_rule_json",
                        "sc_platform_sku.user_limit_enabled",
                        "sc_platform_sku.user_limit_period",
                        "sc_platform_sku.user_limit_quantity",
                        "sc_platform_sku.order_limit_enabled",
                        "sc_platform_sku.phone_bind_limit_enabled",
                        "sc_platform_sku.phone_bind_limit_period",
                        "sc_platform_sku.phone_bind_limit_quantity",
                        "sc_platform_sku.sku_inventory",
                        "sc_platform_sku.main_image_url",
                        "sc_platform_sku.detail_image_urls",
                        "sc_platform_sku.description",
                        "sc_product_category.product_type",
                        "sc_product_category.description",
                        "sc_product_category.type_codes",
                        "sc_fulfillment_acceptance_receipt.project_id",
                        "sc_supply_chain_order.project_id",
                        "sc_supply_chain_order.pricing_quote_id",
                        "sc_supply_chain_order.pricing_version_id",
                        "sc_supply_chain_order.sales_unit_price_cents",
                        "sc_supply_chain_order.product_subtotal_cents",
                        "sc_supply_chain_order.service_fee_cents",
                        "sc_supply_chain_order.user_payable_amount_cents",
                        "sc_supply_chain_order.refundable_service_fee_cents",
                        "sc_supply_chain_order.tax_metadata_json",
                        "sc_platform_sku_pricing_version.effective_config_json",
                        "sc_buyer_sku_pricing_version.platform_pricing_version_id",
                        "sc_buyer_sku_pricing_version.override_json",
                        "sc_buyer_sku_pricing_version.effective_config_json",
                        "sc_supply_chain_order.fulfillment_account_value",
                        "sc_product_category.visible");
        assertThat(firstStartup.projectAuthNonceColumnDefinitions()).containsExactly(
                "app_id:VARCHAR:128:-:NO",
                "nonce:VARCHAR:128:-:NO",
                "created_at:DATETIME:-:3:NO",
                "expire_at:DATETIME:-:3:NO");
        assertThat(firstStartup.projectAuthNoncePrimaryKeyColumns())
                .containsExactly("app_id", "nonce");
        assertThat(firstStartup.changeSetCount()).isEqualTo(43L);
        assertThat(firstStartup.uniqueIndexes())
                .contains("sc_instore_payment_flow.uk_sc_instore_flow_order",
                        "sc_instore_channel_user_binding.uk_sc_instore_user_binding_project",
                        "sc_brand.uk_sc_brand_name",
                        "sc_product_category.uk_sc_category_live_parent_name",
                        "sc_product_type.uk_sc_product_type_name",
                        "sc_platform_sku_pricing_version.uk_sc_platform_sku_pricing_version",
                        "sc_buyer_sku_pricing.uk_sc_buyer_sku_pricing_product",
                        "sc_project_sku_pricing.uk_sc_project_sku_pricing_listing",
                        "sc_standard_pricing_quote.uk_sc_standard_quote_token");
        assertThat(firstStartup.indexes())
                .contains("sc_buyer.idx_sc_buyer_parent_created",
                        "sc_product_category.idx_sc_category_product_type",
                        "sc_supply_chain_order.idx_sc_order_purchase_limit",
                        "sc_supply_chain_order.idx_sc_order_phone_bind_limit");
        assertThat(firstStartup.collations()).containsOnly("utf8mb4_0900_ai_ci");
        assertThat(firstStartup.importedKeys()).isEmpty();
        assertThat(firstStartup.duplicateChangeSets()).isEmpty();

        try (ConfigurableApplicationContext secondContext = applicationContext()) {
            DatabaseSnapshot secondStartup = snapshot(
                    secondContext.getBean("supplyChainDataSource", DataSource.class));
            assertThat(secondStartup.tableNames()).containsExactlyElementsOf(firstStartup.tableNames());
            assertThat(secondStartup.changeSetCount()).isEqualTo(firstStartup.changeSetCount());
            assertThat(secondStartup.duplicateChangeSets()).isEmpty();
        }
    }

    /**
     * 锁定供应链连接会话、数据库时钟与全部时间列的新契约。
     * 设计见
     * {@code docs/superpowers/specs/2026-09-04-supply-chain-database-local-time-design.md#6-验收标准}。
     */
    @Test
    void usesShanghaiDatabaseClockAndDatetime3Columns() throws SQLException {
        try (ConfigurableApplicationContext context = applicationContext()) {
            SupplyChainDatabaseClock databaseClock = context.getBean(SupplyChainDatabaseClock.class);
            Instant before = Instant.now().minusSeconds(1);
            Instant databaseNow = databaseClock.toInstant(databaseClock.now());
            Instant after = Instant.now().plusSeconds(1);
            assertThat(databaseNow).isBetween(before, after);

            DataSource dataSource = context.getBean("supplyChainDataSource", DataSource.class);
            try (Connection connection = dataSource.getConnection()) {
                assertThat(queryLong(connection,
                        "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), CURRENT_TIMESTAMP())"))
                        .isEqualTo(28_800L);
                assertThat(queryLong(connection, """
                        SELECT COUNT(DISTINCT TABLE_NAME)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME LIKE 'sc\\_%'
                        """)).isEqualTo(112L);
                assertThat(queryLong(connection, """
                        SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME LIKE 'sc\\_%'
                          AND DATA_TYPE = 'datetime'
                        """)).isEqualTo(271L);
                assertThat(queryLong(connection, """
                        SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME LIKE 'sc\\_%'
                          AND DATA_TYPE = 'datetime'
                          AND DATETIME_PRECISION <> 3
                        """)).isZero();
                assertThat(queryLong(connection, """
                        SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME LIKE 'sc\\_%'
                          AND DATA_TYPE = 'datetime'
                          AND UPPER(COLUMN_COMMENT) LIKE '%UTC%'
                        """)).isZero();
                assertThat(queryLong(connection, """
                        SELECT COUNT(*)
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME LIKE 'sc\\_%'
                          AND DATA_TYPE = 'datetime'
                          AND COLUMN_COMMENT NOT LIKE '%Asia/Shanghai%'
                        """)).isZero();
            }
        }
    }

    /**
     * 040 将 {@code sc_fulfillment_asset.lifecycle_status} 与 {@link FulfillmentAssetStatus} 对齐，允许 50 已过期。
     */
    @Test
    void fulfillmentAssetLifecycleStatusAllowsExpiredAndRejectsUnknown() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            DataSource dataSource = context.getBean("supplyChainDataSource", DataSource.class);
            try (Connection connection = dataSource.getConnection()) {
                List<String> comments = queryStrings(connection, """
                        SELECT COLUMN_COMMENT
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 'sc_fulfillment_asset'
                          AND COLUMN_NAME = 'lifecycle_status'
                        """);
                assertThat(comments).hasSize(1);
                assertThat(comments.get(0)).contains("50已过期");

                List<String> checkClauses = queryStrings(connection, """
                        SELECT CHECK_CLAUSE
                        FROM information_schema.CHECK_CONSTRAINTS
                        WHERE CONSTRAINT_SCHEMA = DATABASE()
                          AND CONSTRAINT_NAME = 'ck_sc_fulfillment_asset_status'
                        """);
                assertThat(checkClauses).hasSize(1);
                assertThat(checkClauses.get(0)).contains("50");

                try (PreparedStatement insertExpired = connection.prepareStatement("""
                        INSERT INTO sc_fulfillment_asset (
                            asset_no, fulfillment_request_id, b_order_id, operation_id, supplier_id, buyer_id,
                            buyer_user_id, sequence_no, unit_quantity, asset_type, encrypted_payload, masked_value,
                            fingerprint, key_version, lifecycle_status, version, created_at, updated_at)
                        VALUES ('FA_IT_EXPIRED_50', 1, 1, 1, 1, 1, 'it-user', 1, 1, 'VOUCHER_CODE', ?, '****',
                            ?, '1', ?, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """)) {
                    insertExpired.setBytes(1, new byte[]{1});
                    insertExpired.setBytes(2, new byte[32]);
                    insertExpired.setShort(3, FulfillmentAssetStatus.EXPIRED.getCode());
                    assertThat(insertExpired.executeUpdate()).isEqualTo(1);
                }

                try (PreparedStatement insertInvalid = connection.prepareStatement("""
                        INSERT INTO sc_fulfillment_asset (
                            asset_no, fulfillment_request_id, b_order_id, operation_id, supplier_id, buyer_id,
                            buyer_user_id, sequence_no, unit_quantity, asset_type, encrypted_payload, masked_value,
                            fingerprint, key_version, lifecycle_status, version, created_at, updated_at)
                        VALUES ('FA_IT_INVALID_60', 1, 1, 1, 1, 1, 'it-user', 2, 1, 'VOUCHER_CODE', ?, '****',
                            ?, '1', 60, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """)) {
                    insertInvalid.setBytes(1, new byte[]{2});
                    byte[] fingerprint = new byte[32];
                    fingerprint[0] = 2;
                    insertInvalid.setBytes(2, fingerprint);
                    assertThatThrownBy(insertInvalid::executeUpdate).isInstanceOf(SQLException.class);
                }
            }
        }
    }

    private ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return new SpringApplicationBuilder(SupplyChainDataSourceConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String username() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root")
                : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "")
                : MYSQL.getPassword();
    }

    private DatabaseSnapshot snapshot(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            List<String> tableNames = queryStrings(connection, """
                    SELECT LOWER(TABLE_NAME)
                    FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME NOT IN ('DATABASECHANGELOG', 'DATABASECHANGELOGLOCK')
                    ORDER BY TABLE_NAME
                    """);
            List<String> columnNames = queryStrings(connection, """
                    SELECT CONCAT(LOWER(TABLE_NAME), '.', LOWER(COLUMN_NAME))
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                    ORDER BY TABLE_NAME, ORDINAL_POSITION
                    """);
            // Schema 019 防重放表契约：列类型、长度/毫秒精度、非空性及复合主键顺序必须精确稳定。
            // 详见 docs/superpowers/specs/2026-08-23-project-app-auth-design.md#64-noncestore-mysql-防重放。
            List<String> projectAuthNonceColumnDefinitions = queryStrings(connection, """
                    SELECT CONCAT(
                            LOWER(COLUMN_NAME), ':', UPPER(DATA_TYPE), ':',
                            COALESCE(CAST(CHARACTER_MAXIMUM_LENGTH AS CHAR), '-'), ':',
                            COALESCE(CAST(DATETIME_PRECISION AS CHAR), '-'), ':',
                            UPPER(IS_NULLABLE))
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'sc_project_auth_nonce'
                    ORDER BY ORDINAL_POSITION
                    """);
            List<String> projectAuthNoncePrimaryKeyColumns = queryStrings(connection, """
                    SELECT LOWER(COLUMN_NAME)
                    FROM information_schema.KEY_COLUMN_USAGE
                    WHERE CONSTRAINT_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'sc_project_auth_nonce'
                      AND CONSTRAINT_NAME = 'PRIMARY'
                    ORDER BY ORDINAL_POSITION
                    """);
            List<String> uniqueIndexes = queryStrings(connection, """
                    SELECT DISTINCT CONCAT(LOWER(TABLE_NAME), '.', LOWER(INDEX_NAME)) AS index_name
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND NON_UNIQUE = 0
                    ORDER BY index_name
                    """);
            List<String> indexes = queryStrings(connection, """
                    SELECT DISTINCT CONCAT(LOWER(TABLE_NAME), '.', LOWER(INDEX_NAME)) AS index_name
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                    ORDER BY index_name
                    """);
            List<String> collations = queryStrings(connection, """
                    SELECT DISTINCT TABLE_COLLATION
                    FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME NOT IN ('DATABASECHANGELOG', 'DATABASECHANGELOGLOCK')
                    """);
            List<String> importedKeys = queryStrings(connection, """
                    SELECT CONSTRAINT_NAME
                    FROM information_schema.REFERENTIAL_CONSTRAINTS
                    WHERE CONSTRAINT_SCHEMA = DATABASE()
                    """);
            List<String> duplicateChangeSets = queryStrings(connection, """
                    SELECT CONCAT(ID, ':', AUTHOR, ':', FILENAME)
                    FROM DATABASECHANGELOG
                    GROUP BY ID, AUTHOR, FILENAME
                    HAVING COUNT(*) > 1
                    """);
            long changeSetCount = queryLong(connection, "SELECT COUNT(*) FROM DATABASECHANGELOG");
            return new DatabaseSnapshot(tableNames, columnNames,
                    projectAuthNonceColumnDefinitions, projectAuthNoncePrimaryKeyColumns, uniqueIndexes,
                    indexes,
                    collations, importedKeys,
                    duplicateChangeSets, changeSetCount);
        }
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<String> results = new java.util.ArrayList<>();
            while (resultSet.next()) {
                results.add(resultSet.getString(1));
            }
            return results;
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private record DatabaseSnapshot(
            List<String> tableNames,
            List<String> columnNames,
            List<String> projectAuthNonceColumnDefinitions,
            List<String> projectAuthNoncePrimaryKeyColumns,
            List<String> uniqueIndexes,
            List<String> indexes,
            List<String> collations,
            List<String> importedKeys,
            List<String> duplicateChangeSets,
            long changeSetCount) {
    }

}
