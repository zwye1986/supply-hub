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
 * 商品类型与商品分类状态数据字典必须与管理端契约一致，并可安全重复执行。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#53-商品中心数据字典与配置变更}。
 */
class ProductTypeAndCategoryStatusDictionaryLiquibaseTest {

    private static final String PRODUCT_TYPE_STATUS_DICT = "supply_chain_product_type_status";
    private static final String CATEGORY_STATUS_DICT = "supply_chain_category_status";
    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/007-product-type-and-category-status-dictionaries.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersProductTypeAndCategoryStatusDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 007-product-type-and-category-status-dictionaries"));
        assertTrue(releaseChangelog.contains("path: 007-product-type-and-category-status-dictionaries.sql"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
    }

    @Test
    void scriptDefinesProductTypeAndCategoryStatuses() {
        assertTrue(dictionarySql.contains(PRODUCT_TYPE_STATUS_DICT));
        assertTrue(dictionarySql.contains(CATEGORY_STATUS_DICT));
        assertTrue(dictionarySql.contains("商品类型状态"));
        assertTrue(dictionarySql.contains("商品分类状态"));
        assertTrue(dictionarySql.contains("启用"));
        assertTrue(dictionarySql.contains("禁用"));
        assertTrue(dictionarySql.contains("草稿"));
        List.of("'10'", "'20'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少商品类型状态码：" + value));
        List.of("'0'", "'10'", "'20'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少商品分类状态码：" + value));
        assertTrue(dictionarySql.contains("'success'"));
        assertTrue(dictionarySql.contains("'danger'"));
        assertTrue(dictionarySql.contains("'info'"));
        assertFalse(dictionarySql.contains("'90', 'supply_chain_product_type_status'"), "商品类型归档态不得作为管理字典选项暴露");
        assertFalse(dictionarySql.contains("'90', 'supply_chain_category_status'"), "商品分类归档态不得作为管理字典选项暴露");
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
                            + "db/changelog/releases/1.0.0/007-product-type-and-category-status-dictionaries.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到商品类型与分类状态数据字典 Liquibase 变更集");
    }
}
