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
 * SKU 销售建议价模式数据字典 Liquibase 变更集测试。
 * 验证 system-server 迁移注册、三项固定模式以及幂等、非破坏性维护约束。
 * 设计见
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#53-商品中心数据字典与配置变更}。
 */
class SuggestedSalePriceModeDictionaryLiquibaseTest {

    private static final String DICT_TYPE = "supply_chain_suggested_sale_price_mode";
    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/010-suggested-sale-price-mode-dictionary.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersSuggestedSalePriceModeDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 010-suggested-sale-price-mode-dictionary"));
        assertTrue(releaseChangelog.contains("path: 010-suggested-sale-price-mode-dictionary.sql"));
        assertTrue(releaseChangelog.contains("author: supply-chain"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
    }

    @Test
    void scriptDefinesSuggestedSalePriceModeDictionaryAndValues() {
        assertTrue(dictionarySql.contains(DICT_TYPE));
        assertTrue(dictionarySql.contains("SKU销售建议价模式"));
        List.of("'EQUAL_MARKET_PRICE'", "'CUSTOM'", "'FIXED'")
                .forEach(value -> assertTrue(dictionarySql.contains(value),
                        () -> "缺少销售建议价模式字典值：" + value));
        List.of("'等于市场价'", "'自定义设置'", "'固定值'")
                .forEach(label -> assertTrue(dictionarySql.contains(label),
                        () -> "缺少销售建议价模式字典标签：" + label));
    }

    @Test
    void scriptIsIdempotentAndNonDestructive() {
        String normalized = dictionarySql.toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("where not exists"));
        assertTrue(dictionarySql.contains("UPDATE system_dict_type"));
        assertTrue(dictionarySql.contains("UPDATE system_dict_data"));
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
                            + "db/changelog/releases/1.0.0/010-suggested-sale-price-mode-dictionary.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到销售建议价模式数据字典 Liquibase 变更集");
    }
}
