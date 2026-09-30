package com.aifuli.module.supplychain.dict;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 040 只追加删除封闭枚举 CHECK，禁止回改 024。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d34）。
 */
class ProductClassificationCategoryCodeLiquibaseTest {

    private static String migrationSql;
    private static String releaseChangelog;
    private static String historical024;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        Path releaseDir = repoRoot.resolve(
                "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0");
        migrationSql = Files.readString(releaseDir.resolve("040-product-classification-category-code.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(releaseDir.resolve("db.changelog-1.0.0.yaml"), StandardCharsets.UTF_8);
        historical024 = Files.readString(releaseDir.resolve("024-admin-form-dictionary-codes.sql"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersMigration() {
        assertTrue(releaseChangelog.contains("id: 040-product-classification-category-code"));
        assertTrue(releaseChangelog.contains("040-product-classification-category-code.sql"));
    }

    @Test
    void dropsClassificationChecksWithoutRewriting024() {
        assertTrue(migrationSql.contains("DROP CHECK ck_sc_spu_product_classification"));
        assertTrue(migrationSql.contains("DROP CHECK ck_sc_supplier_scope_product_classification"));
        assertTrue(historical024.contains("ck_sc_spu_product_classification"));
        assertTrue(historical024.contains("ck_sc_supplier_scope_product_classification"));
        assertFalse(migrationSql.contains("id: 024"));
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
