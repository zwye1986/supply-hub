package com.aifuli.module.system.service.dict;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品列表兑换方式与 SKU 状态筛选字典必须与管理端查询契约一致，并可安全重复执行。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page}。
 */
class ProductListFilterDictionaryLiquibaseTest {

    private static final String FULFILLMENT_TYPE_DICT = "supply_chain_fulfillment_type";
    private static final String SKU_STATUS_DICT = "supply_chain_sku_status";
    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/006-product-list-filter-dictionaries.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersProductListFilterDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 006-product-list-filter-dictionaries"));
        assertTrue(releaseChangelog.contains("path: 006-product-list-filter-dictionaries.sql"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
    }

    @Test
    void scriptDefinesPrototypeFulfillmentTypesAndSkuStatuses() {
        assertTrue(dictionarySql.contains(FULFILLMENT_TYPE_DICT));
        assertTrue(dictionarySql.contains(SKU_STATUS_DICT));
        List.of("'CARD_CODE_SECRET'", "'DIRECT_RECHARGE'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少兑换方式：" + value));
        List.of("'0'", "'10'", "'20'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少 SKU 商品状态：" + value));
        assertFalse(dictionarySql.contains("'90', 'supply_chain_sku_status'"), "归档状态不得作为商品列表筛选项");
    }

    @Test
    void scriptIsIdempotentAndNonDestructive() {
        String normalized = dictionarySql.toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("where not exists"));
        assertTrue(dictionarySql.contains("CAST(existing.type AS BINARY)"));
        assertTrue(dictionarySql.contains("CAST(existing.dict_type AS BINARY)"));
        assertTrue(dictionarySql.contains("CAST(existing.value AS BINARY)"));
        assertFalse(normalized.contains("delete from"));
        assertFalse(normalized.contains("drop table"));
        assertFalse(normalized.contains("truncate"));
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                            + "db/changelog/releases/1.0.0/006-product-list-filter-dictionaries.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到商品列表筛选数据字典 Liquibase 变更集");
    }
}
