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
 * supply-chain Liquibase `024-admin-form-dictionary-codes` 结构校验：只改列不回改历史 changeSet。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#52-供应链}。
 */
class AdminFormDictionaryCodesLiquibaseTest {

    private static String migrationSql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        migrationSql = Files.readString(repoRoot.resolve(
                "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/024-admin-form-dictionary-codes.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersMigration() {
        assertTrue(releaseChangelog.contains("id: 024-admin-form-dictionary-codes"));
        assertTrue(releaseChangelog.contains("024-admin-form-dictionary-codes.sql"));
    }

    @Test
    void doesNotTouchHistoricalChangeSets() {
        for (int i = 1; i <= 23; i++) {
            String needle = "id: " + String.format("%03d", i);
            assertFalse(migrationSql.contains(needle), () -> "024 不得改动历史 changeSet：" + needle);
        }
    }

    @Test
    void supplierScopeMovesToThreeCodes() {
        assertTrue(migrationSql.contains("DROP COLUMN category_l1_id"));
        assertTrue(migrationSql.contains("ADD COLUMN sellable_category"));
        assertTrue(migrationSql.contains("ADD COLUMN product_type"));
        assertTrue(migrationSql.contains("ADD COLUMN product_classification"));
        assertTrue(migrationSql.contains(
                "uk_sc_supplier_scope_triple (supplier_id, sellable_category, product_type, product_classification)"));
    }

    @Test
    void buyerScopeMovesToKindAndCode() {
        assertTrue(migrationSql.contains("DROP COLUMN category_id"));
        assertTrue(migrationSql.contains("DROP COLUMN category_level"));
        assertTrue(migrationSql.contains("ADD COLUMN scope_kind"));
        assertTrue(migrationSql.contains("ADD COLUMN scope_code"));
    }

    @Test
    void spuGetsThreeCodesAndNullablePrimaryCategory() {
        assertTrue(migrationSql.contains("MODIFY COLUMN primary_category_id BIGINT NULL"));
        assertTrue(migrationSql.contains("ADD COLUMN sellable_category VARCHAR(32) NULL"));
        assertTrue(migrationSql.contains("ADD COLUMN product_type VARCHAR(32) NULL"));
        assertTrue(migrationSql.contains("ADD COLUMN product_classification VARCHAR(32) NULL"));
    }

    @Test
    void clearsChildModeBeforeScopeAndBuyerScope() {
        int modeDelete = migrationSql.indexOf(
                "DELETE FROM sc_supplier_supply_scope_mode");
        int scopeDelete = migrationSql.indexOf("DELETE FROM sc_supplier_supply_scope;");
        int buyerDelete = migrationSql.indexOf("DELETE FROM sc_buyer_category_scope;");
        assertTrue(modeDelete >= 0 && scopeDelete > modeDelete,
                "必须先清空 mode 子表再清空 scope 主表");
        assertTrue(buyerDelete > scopeDelete, "分销商范围清空应在供货配置清空之后");
    }

    @Test
    void checkConstraintsMatchClosedEnumLiterals() {
        assertTrue(migrationSql.contains("'EQUITY', 'PHYSICAL', 'MOVIE', 'JD', 'DINING', 'TAKEOUT', 'SUPERMARKET', 'HEMA', 'RTMART', 'LIFE'"));
        assertTrue(migrationSql.contains("'EQUITY_PRODUCT', 'MOVIE', 'DINING', 'JD', 'VIP'"));
        assertTrue(migrationSql.contains("'ELECTRONICS', 'VIRTUAL_CARD', 'MOVIE_TICKET'"));
        assertTrue(migrationSql.contains("scope_kind IN ('SELLABLE', 'PRODUCT_TYPE')"));
        assertTrue(migrationSql.contains(
                "uk_sc_buyer_category_scope (buyer_id, scope_kind, scope_code)"));
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
