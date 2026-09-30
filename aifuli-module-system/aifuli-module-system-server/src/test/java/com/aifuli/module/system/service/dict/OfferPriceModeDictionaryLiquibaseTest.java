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
 * 合作商品供应价模式数据字典 Liquibase 变更集测试。
 * 验证 system-server 迁移注册、三项固定模式以及幂等、非破坏性维护约束。
 * 设计见
 * {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#25-供应价模式-offerpricemode}。
 */
class OfferPriceModeDictionaryLiquibaseTest {

    private static final String DICT_TYPE = "supply_chain_offer_price_mode";
    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/012-offer-price-mode-dictionary.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersOfferPriceModeDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 012-offer-price-mode-dictionary"));
        assertTrue(releaseChangelog.contains("path: 012-offer-price-mode-dictionary.sql"));
        assertTrue(releaseChangelog.contains("author: supply-chain"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
    }

    @Test
    void scriptDefinesOfferPriceModeDictionaryAndValues() {
        assertTrue(dictionarySql.contains(DICT_TYPE));
        assertTrue(dictionarySql.contains("供应价模式"));
        List.of("'FIXED'", "'REALTIME'", "'DISCOUNT'")
                .forEach(value -> assertTrue(dictionarySql.contains(value),
                        () -> "缺少供应价模式字典值：" + value));
        List.of("'固定值'", "'实时更新'", "'按供货折扣'")
                .forEach(label -> assertTrue(dictionarySql.contains(label),
                        () -> "缺少供应价模式字典标签：" + label));
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
                            + "db/changelog/releases/1.0.0/012-offer-price-mode-dictionary.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到供应价模式数据字典 Liquibase 变更集");
    }
}
