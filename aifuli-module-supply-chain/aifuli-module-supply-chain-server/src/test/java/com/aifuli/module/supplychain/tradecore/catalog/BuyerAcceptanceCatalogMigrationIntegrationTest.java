package com.aifuli.module.supplychain.tradecore.catalog;

import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

/** 060真实升级与重启幂等，存量MANUAL字节/价/生命周期不得回填；见桥接设计§4/§13.2。 */
class BuyerAcceptanceCatalogMigrationIntegrationTest {
    @Test void upgradePreservesLegacyValuesAndEnforcesRealIdentityAndCommercialSourceUniqueness() throws Exception {
        try (var mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("supply_chain")
                .withUsername("supply_chain").withPassword("supply_chain")) {
            mysql.start();
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            var jdbc = new JdbcTemplate(source);
            update(source, 61);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE id='060-buyer-acceptance-catalog'", Long.class)).isZero();
            jdbc.update("INSERT INTO sc_buyer_listing(id,listing_no,buyer_id,platform_sku_id,buyer_sku_code,current_version_id,status,version,created_by,created_at,updated_by,updated_at) VALUES (901,'LST_LEGACY',51,71,'  bpd_旧—编码  ',902,20,7,3,'2026-10-01 01:02:03.456',4,'2026-10-02 02:03:04.567')");
            jdbc.update("INSERT INTO sc_buyer_listing_version(id,listing_id,version_no,buyer_settlement_unit_price,currency,display_name,min_quantity,max_quantity,valid_from,publish_reason,published_by,published_at,created_at) VALUES (902,901,1,9876,'CNY','原人工价格',1,1,'2026-10-01 01:02:03.456','原发布',3,'2026-10-01 01:02:03.456','2026-10-01 01:02:03.456')");
            var identityBefore = jdbc.queryForMap("SELECT * FROM sc_buyer_listing WHERE id=901");
            var priceBefore = jdbc.queryForMap("SELECT * FROM sc_buyer_listing_version WHERE id=902");
            update(source, null);
            var identityAfter = jdbc.queryForMap("SELECT * FROM sc_buyer_listing WHERE id=901");
            assertThat(identityAfter.remove("management_mode")).isEqualTo("MANUAL");
            assertThat(identityAfter.remove("buyer_product_id")).isNull();
            assertThat(identityAfter).isEqualTo(identityBefore);
            var priceAfter = jdbc.queryForMap("SELECT * FROM sc_buyer_listing_version WHERE id=902");
            assertThat(priceAfter.remove("source_buyer_pricing_version_id")).isNull();
            assertThat(priceAfter.remove("commercial_snapshot_digest")).isNull();
            assertThat(priceAfter).isEqualTo(priceBefore);
            var checksums = jdbc.queryForList("SELECT ID,MD5SUM FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED");
            assertThat(checksums).hasSize(63);
            update(source, null);
            assertThat(jdbc.queryForList("SELECT ID,MD5SUM FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED")).isEqualTo(checksums);
            assertThatThrownBy(() -> jdbc.update("UPDATE sc_buyer_listing SET management_mode='AUTO_SELECTION' WHERE id=901"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLException.class)
                    .satisfies(failure -> assertThat(((java.sql.SQLException) failure.getCause()).getErrorCode()).isEqualTo(3819));
            assertThatThrownBy(() -> jdbc.update("UPDATE sc_buyer_listing SET buyer_product_id=101 WHERE id=901"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLException.class)
                    .satisfies(failure -> assertThat(((java.sql.SQLException) failure.getCause()).getErrorCode()).isEqualTo(3819));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='sc_buyer_listing' AND index_name='uk_sc_listing_buyer_product' AND non_unique=0", Long.class)).isEqualTo(1L);
            // 隔离迁移约束负例，不是管理端可售闭环；其它唯一列不同，定位060新增真实唯一键。
            jdbc.update("INSERT INTO sc_buyer_listing(id,listing_no,buyer_id,platform_sku_id,buyer_sku_code,status,version,created_at,updated_at,management_mode,buyer_product_id) VALUES (903,'LST_UNIQUE_A',52,72,'BPD_UNIQUE_A',0,0,NOW(3),NOW(3),'AUTO_SELECTION',101)");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO sc_buyer_listing(id,listing_no,buyer_id,platform_sku_id,buyer_sku_code,status,version,created_at,updated_at,management_mode,buyer_product_id) VALUES (904,'LST_UNIQUE_B',53,73,'BPD_UNIQUE_B',0,0,NOW(3),NOW(3),'AUTO_SELECTION',101)"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
                    .satisfies(error -> assertUniqueError(error, "uk_sc_listing_buyer_product"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_listing WHERE buyer_product_id=101", Long.class)).isEqualTo(1);
            jdbc.update("UPDATE sc_buyer_listing_version SET source_buyer_pricing_version_id=701,commercial_snapshot_digest=REPEAT('a',32) WHERE id=902");
            String copyVersion = "INSERT INTO sc_buyer_listing_version(listing_id,version_no,buyer_settlement_unit_price,currency,display_name,min_quantity,max_quantity,valid_from,publish_reason,published_by,published_at,created_at,source_buyer_pricing_version_id,commercial_snapshot_digest) SELECT listing_id,?,buyer_settlement_unit_price,currency,display_name,min_quantity,max_quantity,valid_from,publish_reason,published_by,published_at,created_at,?,? FROM sc_buyer_listing_version WHERE id=902";
            assertThatThrownBy(() -> jdbc.update(copyVersion, 2, 701L, "a".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
                    .satisfies(error -> assertUniqueError(error, "uk_sc_listing_commercial_source"));
            // 同摘要不同来源、同来源不同摘要合法；MANUAL空来源可多版本，不收紧历史契约。
            jdbc.update(copyVersion, 2, 702L, "a".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update(copyVersion, 3, 701L, "b".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update(copyVersion, 4, null, null);
            jdbc.update(copyVersion, 5, null, null);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_listing_version WHERE listing_id=901 AND source_buyer_pricing_version_id=701 AND commercial_snapshot_digest=REPEAT('a',32)", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_listing_version WHERE listing_id=901", Long.class)).isEqualTo(5);
        }
    }
    private static void assertUniqueError(Throwable error, String key) {
        assertThat(error.getCause()).isInstanceOf(java.sql.SQLException.class);
        var sql = (java.sql.SQLException) error.getCause();
        assertThat(sql.getErrorCode()).isEqualTo(1062);
        assertThat(sql.getMessage()).contains(key);
    }
    private static void update(DataSource source, Integer count) throws Exception {
        try (var connection = source.getConnection()) {
            var db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var migration = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), db)) {
                if (count == null) migration.update(new Contexts(), new LabelExpression());
                else migration.update(count, new Contexts(), new LabelExpression());
            }
        }
    }
}
