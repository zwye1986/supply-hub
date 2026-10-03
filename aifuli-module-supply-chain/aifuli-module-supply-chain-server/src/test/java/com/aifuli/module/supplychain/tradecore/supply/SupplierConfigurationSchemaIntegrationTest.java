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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 供给发布共享地基 DDL（变更集 053-057）的真实 MySQL 模式不变量验证。
 *
 * <p>锁定四条地基约束：f1 未退役商品外部编码每供应商唯一（053/057）、AD-3 凭据集/项建表与唯一键（054）、
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
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database)) {
                // 先重放至 056（58 个已登记变更集），植入退役历史，验证 057 的存量升级及重复启动。
                // 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#154-退役商品释放外部编码。
                liquibase.update(58, new Contexts(), new LabelExpression());
                insertOffer(900_051L, "OFR_PRE_057", "EXT-PRE-057");
                jdbc.update("UPDATE sc_supplier_offer SET status = 90 WHERE offer_no = 'OFR_PRE_057'");
                liquibase.update(1, new Contexts(), new LabelExpression());
                jdbc.update("INSERT INTO sc_credential_slot(id,slot_no,provider_type,secret_ref,masked_hint,active_secret_version,status,created_at,updated_at) VALUES(70001,'CRD_PRE_058','ENV','env:QNM_LEGACY','masked','v1',10,?,?)",now(),now());
                insertConnector(900_052L, "CON_PRE_058", null);
                Long legacyConnector = jdbc.queryForObject("SELECT id FROM sc_supplier_connector WHERE connector_no='CON_PRE_058'",Long.class);
                jdbc.update("INSERT INTO sc_supplier_connector_version(connector_id,version_no,base_url,credential_slot_id,protocol_version,connect_timeout_ms,read_timeout_ms,adapter_config_schema_version,adapter_config_json,validation_status,config_digest,status,created_at,updated_at) VALUES(?,1,'https://api.qinameng.com',70001,'V1',1000,2000,1,'{}',10,?,10,?,?)",legacyConnector,new byte[32],now(),now());
                liquibase.update(new Contexts(), new LabelExpression());
                liquibase.update(new Contexts(), new LabelExpression());
                assertThat(jdbc.queryForObject("SELECT credential_slot_id FROM sc_supplier_connector_version WHERE connector_id=?",Long.class,legacyConnector)).isEqualTo(70001L);
                assertThat(jdbc.queryForObject("SELECT credential_set_id FROM sc_supplier_connector_version WHERE connector_id=?",Long.class,legacyConnector)).isNull();
                assertThat(jdbc.queryForObject("SELECT credential_item_snapshot FROM sc_supplier_connector_version WHERE connector_id=?",String.class,legacyConnector)).isNull();
                assertThat(jdbc.queryForObject("SELECT config_digest FROM sc_supplier_connector_version WHERE connector_id=?",byte[].class,legacyConnector)).isEqualTo(new byte[32]);
                assertThat(jdbc.queryForObject("SELECT external_sku_code FROM sc_supplier_offer "
                        + "WHERE offer_no = 'OFR_PRE_057'", String.class)).isEqualTo("EXT-PRE-057");
                insertOffer(900_051L, "OFR_POST_057", "EXT-PRE-057");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class)).isEqualTo(60L);
            }
        }
    }

    /** 053/057：同一供应商下未退役 external_sku_code 唯一；历史/未录入的多行 NULL 不冲突，跨供应商可复用同码。 */
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

    /** 057：退役行保留原编码但释放唯一槽；草稿、发布和暂停仍互斥。 */
    @Test
    void retiredOfferReleasesExternalSkuWithoutErasingHistory() {
        long supplierId = 900_151L;
        insertOffer(supplierId, "OFR_RETIRED", "EXT-REUSE");
        jdbc.update("UPDATE sc_supplier_offer SET status = 90 WHERE offer_no = 'OFR_RETIRED'");
        insertOffer(supplierId, "OFR_REPLACEMENT", "EXT-REUSE");
        assertThat(jdbc.queryForObject("SELECT external_sku_code FROM sc_supplier_offer "
                + "WHERE offer_no = 'OFR_RETIRED'", String.class)).isEqualTo("EXT-REUSE");
        for (short status : new short[]{0, 10, 20}) {
            jdbc.update("UPDATE sc_supplier_offer SET status = ? WHERE offer_no = 'OFR_REPLACEMENT'", status);
            assertThatThrownBy(() -> insertOffer(supplierId, "OFR_DUPLICATE", "EXT-REUSE"))
                    .isInstanceOf(DuplicateKeyException.class);
        }
        jdbc.update("UPDATE sc_supplier_offer SET status = 90 WHERE offer_no = 'OFR_REPLACEMENT'");
        insertOffer(supplierId, "OFR_REPLACEMENT_2", "EXT-REUSE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_offer WHERE supplier_id = ? "
                + "AND external_sku_code = 'EXT-REUSE'", Long.class, supplierId)).isEqualTo(3L);
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

    @Test
    void explicitCredentialBindingColumnsAndLongEnvelopeStorage() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='sc_supplier_connector_version' AND COLUMN_NAME IN ('credential_set_id','credential_item_snapshot')", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='sc_credential_item' AND COLUMN_NAME='secret_ref'", String.class)).isEqualTo("text");
    }

    @Test
    void credentialBindingCheckRequiresExactlyOneReference() {
        insertConnector(900_301L,"CON_XOR",null);
        Long connector=jdbc.queryForObject("SELECT id FROM sc_supplier_connector WHERE connector_no='CON_XOR'",Long.class);
        String sql="INSERT INTO sc_supplier_connector_version(connector_id,version_no,base_url,credential_slot_id,credential_set_id,protocol_version,connect_timeout_ms,read_timeout_ms,adapter_config_schema_version,adapter_config_json,validation_status,config_digest,status,created_at,updated_at) VALUES(?,?,'https://api.qinameng.com',?,?,'V1',1000,2000,1,'{}',0,?,0,?,?)";
        for (Long[] references : java.util.List.of(new Long[]{null,null}, new Long[]{1L,2L})) {
            Throwable failure = catchThrowable(() -> jdbc.update(sql,connector,1,references[0],references[1],new byte[32],now(),now()));
            assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            assertThat(cause).isInstanceOf(java.sql.SQLException.class);
            assertThat(((java.sql.SQLException) cause).getErrorCode()).isEqualTo(3819);
            assertThat(cause.getMessage()).contains("chk_sc_connector_credential_binding");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_connector_version WHERE connector_id=?",Long.class,connector)).isZero();
        }
        jdbc.update(sql,connector,1,1L,null,new byte[32],now(),now());
        jdbc.update(sql,connector,2,null,2L,new byte[32],now(),now());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_connector_version WHERE connector_id=?",Long.class,connector)).isEqualTo(2L);
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
