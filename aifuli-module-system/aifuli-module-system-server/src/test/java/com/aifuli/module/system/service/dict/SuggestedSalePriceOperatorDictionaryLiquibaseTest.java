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
 * SKU 销售建议价 CUSTOM 运算符数据字典 Liquibase 变更集测试。
 * 验证追加迁移注册、四项固定运算符以及幂等、非破坏性维护约束。
 * 设计见
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#53-商品中心数据字典与配置变更}。
 */
class SuggestedSalePriceOperatorDictionaryLiquibaseTest {

    private static final String DICT_TYPE = "supply_chain_suggested_sale_price_operator";
    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/011-suggested-sale-price-operator-dictionary.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersSuggestedSalePriceOperatorDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 011-suggested-sale-price-operator-dictionary"));
        assertTrue(releaseChangelog.contains("path: 011-suggested-sale-price-operator-dictionary.sql"));
        assertTrue(releaseChangelog.contains("author: supply-chain"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
    }

    @Test
    void scriptDefinesSuggestedSalePriceOperatorDictionaryAndValues() {
        assertTrue(dictionarySql.contains(DICT_TYPE));
        assertTrue(dictionarySql.contains("SKU销售建议价运算符"));
        List.of("'ADD'", "'SUBTRACT'", "'MULTIPLY'", "'DIVIDE'")
                .forEach(value -> assertTrue(dictionarySql.contains(value),
                        () -> "缺少销售建议价运算符字典值：" + value));
        List.of("'加'", "'减'", "'乘'", "'除'")
                .forEach(label -> assertTrue(dictionarySql.contains(label),
                        () -> "缺少销售建议价运算符字典标签：" + label));
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
                            + "db/changelog/releases/1.0.0/011-suggested-sale-price-operator-dictionary.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到销售建议价运算符数据字典 Liquibase 变更集");
    }
}
