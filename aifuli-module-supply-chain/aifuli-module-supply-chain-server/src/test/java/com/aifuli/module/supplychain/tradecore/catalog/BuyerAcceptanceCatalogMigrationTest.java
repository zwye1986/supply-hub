package com.aifuli.module.supplychain.tradecore.catalog;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/** 受理目录增量来源契约；见 docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#4-数据库增量与来源所有权。 */
class BuyerAcceptanceCatalogMigrationTest {
    @Test
    void registersIndependentMigrationWithManualDefaultAndAutomaticOwnershipChecks() throws Exception {
        String root = "/db/changelog/releases/1.0.0/";
        try (InputStream input = getClass().getResourceAsStream(root + "060-buyer-acceptance-catalog.sql")) {
            assertThat(input).as("下一独立060目录迁移必须存在").isNotNull();
            String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(ddl).contains("management_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL'",
                    "buyer_product_id BIGINT NULL", "UNIQUE KEY uk_sc_listing_buyer_product (buyer_product_id)",
                    "management_mode = 'MANUAL' AND buyer_product_id IS NULL",
                    "management_mode = 'AUTO_SELECTION' AND buyer_product_id IS NOT NULL",
                    "source_buyer_pricing_version_id BIGINT NULL", "commercial_snapshot_digest BINARY(32) NULL",
                    "basis_digest BINARY(32) NULL", "basis_json JSON NULL", "valid_until DATETIME(3) NULL")
                    .doesNotContain("UPDATE sc_buyer_listing", "FOREIGN KEY");
        }
        try (InputStream input = getClass().getResourceAsStream(root + "db.changelog-1.0.0.yaml")) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("id: 060-buyer-acceptance-catalog", "path: 060-buyer-acceptance-catalog.sql");
        }
    }
}
