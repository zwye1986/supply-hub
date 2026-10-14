package com.aifuli.module.supplychain.tradecore.buyer;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

/** 067真实升级保留历史资金身份，见 docs/superpowers/specs/2026-10-14-project-settlement-buyer-design.md#3-数据与资金。 */
class ProjectSettlementMigrationIntegrationTest {
    @Test
    void backfillsHistoricalProjectAndRequestWithoutChangingFutureIndependentSettlement() throws Exception {
        try (var mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("settlement_migration")) {
            mysql.start();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
                try (var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database)) {
                    var changes = liquibase.getDatabaseChangeLog().getChangeSets();
                    int beforeSettlement = java.util.stream.IntStream.range(0, changes.size())
                            .filter(i -> changes.get(i).getId().equals("067-project-settlement-buyer"))
                            .findFirst().orElseThrow();
                    liquibase.update(beforeSettlement, new Contexts(), new LabelExpression());
                    try (var stmt = connection.createStatement()) {
                        stmt.executeUpdate("""
                                INSERT INTO sc_buyer_project (id, project_code, project_name, buyer_id, catalog_mode,
                                    status, created_at, updated_at)
                                VALUES (501, 'PRJ_MIGRATION', '历史项目', 51, 'MANAGED', 10, NOW(3), NOW(3))
                                """);
                        stmt.executeUpdate("""
                                INSERT INTO sc_fulfillment_request (id, request_no, order_id, buyer_id, listing_id,
                                    listing_version_id, platform_sku_id, target_schema_code, target_schema_version,
                                    target_ciphertext, target_digest, target_key_version, requested_quantity, status,
                                    created_at, updated_at)
                                VALUES (601, 'FR_MIGRATION', 701, 51, 801, 901, 101, 'DIRECT_RECHARGE', 1,
                                    X'01', UNHEX(SHA2('historical',256)), 'test-v1', 1, 20, NOW(3), NOW(3))
                                """);
                    }
                    liquibase.update(new Contexts(), new LabelExpression());
                    try (var stmt = connection.createStatement()) {
                        try (var rows = stmt.executeQuery("SELECT settlement_buyer_id FROM sc_buyer_project WHERE id=501")) {
                            assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(51L);
                        }
                        try (var rows = stmt.executeQuery("SELECT settlement_buyer_id FROM sc_fulfillment_request WHERE id=601")) {
                            assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(51L);
                        }
                        stmt.executeUpdate("UPDATE sc_buyer_project SET settlement_buyer_id=52 WHERE id=501");
                    }
                    liquibase.update(new Contexts(), new LabelExpression());
                    try (var stmt = connection.createStatement(); var rows = stmt.executeQuery(
                            "SELECT p.settlement_buyer_id,r.settlement_buyer_id FROM sc_buyer_project p JOIN sc_fulfillment_request r ON r.id=601 WHERE p.id=501")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(52L);
                        assertThat(rows.getLong(2)).isEqualTo(51L);
                    }
                }
            }
        }
    }
}
