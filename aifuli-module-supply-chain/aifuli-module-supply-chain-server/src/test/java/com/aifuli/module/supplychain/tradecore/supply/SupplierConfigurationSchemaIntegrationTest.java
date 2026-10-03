package com.aifuli.module.supplychain.tradecore.supply;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 供给发布共享地基 DDL（变更集 053-056）的真实 MySQL 模式不变量验证。
 *
 * <p>锁定四条地基约束：f1 供应商外部商品编码每供应商唯一（053）、AD-3 凭据集/项建表与唯一键（054）、
 * AD-5 连接器版本 {@code extra_config} 可空 JSON 列（055）、D1 每供应商至多一个已发布连接器
 * （{@code current_version_id} 非空）的 {@code published_flag} 生成列唯一键（056）。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#5-共享地基规格唯一出处与-hua_sheng-文共建i1}。</p>
 */
class SupplierConfigurationSchemaIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain_schema").withUsername("supply_chain").withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateSchema() throws Exception {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
        DataSource dataSource = dataSource();
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
        jdbc = new JdbcTemplate(dataSource);
    }

    /** 053：同一供应商下 external_sku_code 唯一；历史/未录入的多行 NULL 不冲突，跨供应商可复用同码。 */
    @Test
    void externalSkuCodeIsUniquePerSupplierButNullable() {
        long supplierId = 900_101L;
        insertOffer(supplierId, "OFR_EXT_A", "EXT-1");
        assertThatThrownBy(() -> insertOffer(supplierId, "OFR_EXT_B", "EXT-1"))
                .isInstanceOf(DuplicateKeyException.class);
        // 多行 NULL 允许（历史行无值不阻塞），跨供应商同码允许。
        insertOffer(supplierId, "OFR_EXT_NULL_1", null);
        insertOffer(supplierId, "OFR_EXT_NULL_2", null);
        insertOffer(supplierId + 1, "OFR_EXT_OTHER", "EXT-1");
    }

    /** 054：凭据集 (supplier_id,set_code) 唯一、凭据项 (credential_set_id,item_key) 唯一。 */
    @Test
    void credentialSetAndItemEnforceUniqueKeys() {
        long supplierId = 900_201L;
        jdbc.update("INSERT INTO sc_credential_set (supplier_id, set_code, status, created_at, updated_at) "
                + "VALUES (?, 'SET-1', 10, ?, ?)", supplierId, now(), now());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sc_credential_set (supplier_id, set_code, status, created_at, updated_at) "
                        + "VALUES (?, 'SET-1', 10, ?, ?)", supplierId, now(), now()))
                .isInstanceOf(DuplicateKeyException.class);
        Long setId = jdbc.queryForObject(
                "SELECT id FROM sc_credential_set WHERE supplier_id = ? AND set_code = 'SET-1'",
                Long.class, supplierId);
        insertCredentialItem(setId, "appSecret");
        assertThatThrownBy(() -> insertCredentialItem(setId, "appSecret"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /** 055：连接器版本新增 extra_config 为可空 JSON 列（密钥类禁入由服务层守卫，模式层只验列形态）。 */
    @Test
    void connectorVersionExtraConfigIsNullableJsonColumn() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'sc_supplier_connector_version' AND COLUMN_NAME = 'extra_config' "
                        + "AND DATA_TYPE = 'json' AND IS_NULLABLE = 'YES'",
                Long.class)).isEqualTo(1L);
    }

    /** 056：每供应商至多一个已发布连接器（current_version_id 非空）；草稿/退役（该列为空）不占用唯一槽，跨供应商互不影响。 */
    @Test
    void atMostOnePublishedConnectorPerSupplier() {
        long supplierId = 900_401L;
        // current_version_id 非空 → published_flag=1；同供应商第二条已发布连接器触发唯一键冲突。
        insertConnector(supplierId, "CON_PUB_1", 7001L);
        assertThatThrownBy(() -> insertConnector(supplierId, "CON_PUB_2", 7002L))
                .isInstanceOf(DuplicateKeyException.class);
        // current_version_id 为空（草稿建即 status=10 但未发布、或退役清空）→ published_flag 为 NULL，可多条。
        insertConnector(supplierId, "CON_DRAFT_1", null);
        insertConnector(supplierId, "CON_DRAFT_2", null);
        // 另一供应商可有自己的已发布连接器。
        insertConnector(supplierId + 1, "CON_PUB_OTHER", 7003L);
    }

    private static void insertOffer(long supplierId, String offerNo, String externalSkuCode) {
        jdbc.update("INSERT INTO sc_supplier_offer (offer_no, offer_code, supplier_id, platform_sku_id, "
                        + "external_sku_code, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 10, ?, ?)",
                offerNo, offerNo, supplierId, 11L, externalSkuCode, now(), now());
    }

    private static void insertConnector(long supplierId, String connectorNo, Long currentVersionId) {
        jdbc.update("INSERT INTO sc_supplier_connector (connector_no, supplier_id, connector_code, "
                        + "adapter_type, current_version_id, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'QNM_GAME_ORDER_V1', ?, 10, ?, ?)",
                connectorNo, supplierId, connectorNo, currentVersionId, now(), now());
    }

    private static void insertCredentialItem(long setId, String itemKey) {
        jdbc.update("INSERT INTO sc_credential_item (credential_set_id, item_key, provider_type, secret_ref, "
                        + "active_secret_version, status, created_at, updated_at) VALUES (?, ?, 'ENV', 'env:X', 1, 10, ?, ?)",
                setId, itemKey, now(), now());
    }

    private static LocalDateTime now() {
        return LocalDateTime.of(2026, 10, 3, 12, 0, 0);
    }

    private static DataSource dataSource() {
        String url = EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
        String username = EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root") : MYSQL.getUsername();
        String password = EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "") : MYSQL.getPassword();
        return new DriverManagerDataSource(url, username, password);
    }
}
