package com.aifuli.module.supplychain.tradecore.catalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 041 只追加 SKU 资料库存列，禁止回改历史 changeSet。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d38）。
 */
class SkuInventoryLiquibaseTest {

    private static final String DDL_COMMENT = "SKU库存数量，管理端创建或编辑商品时设置";

    private static String migrationSql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path releaseDir = findRepoRoot().resolve(
                "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0");
        migrationSql = Files.readString(releaseDir.resolve("041-sku-inventory.sql"), StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(releaseDir.resolve("db.changelog-1.0.0.yaml"), StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersMigration() {
        assertThat(releaseChangelog)
                .contains("id: 041-sku-inventory")
                .contains("path: 041-sku-inventory.sql");
    }

    @Test
    void addsNonNegativeSkuInventoryAfterPhoneBindLimit() {
        assertThat(migrationSql)
                .contains("ADD COLUMN sku_inventory BIGINT NOT NULL DEFAULT 0 COMMENT '" + DDL_COMMENT + "'")
                .contains("AFTER phone_bind_limit_quantity")
                .contains("ADD CONSTRAINT ck_sc_sku_inventory CHECK (sku_inventory >= 0)")
                .contains("不是 InventorySource")
                .doesNotContain("id: 040");
    }

    private static Path findRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                            + "db/changelog/releases/1.0.0/023-admin-invoice.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repo root not found");
    }
}
