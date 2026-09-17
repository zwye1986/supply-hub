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
 * SKU 限购周期数据字典必须与管理端契约及 PurchaseLimitPeriod 枚举一致，并可安全重复执行。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#53-商品中心数据字典与配置变更}。
 */
class SkuPurchaseLimitPeriodDictionaryLiquibaseTest {

    private static final String LIMIT_PERIOD_DICT = "supply_chain_purchase_limit_period";
    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/008-sku-purchase-limit-period-dictionary.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersSkuPurchaseLimitPeriodDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 008-sku-purchase-limit-period-dictionary"));
        assertTrue(releaseChangelog.contains("path: 008-sku-purchase-limit-period-dictionary.sql"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
    }

    @Test
    void scriptDefinesPurchaseLimitPeriodDictionaryAndValues() {
        assertTrue(dictionarySql.contains(LIMIT_PERIOD_DICT));
        assertTrue(dictionarySql.contains("SKU限购周期"));
        assertTrue(dictionarySql.contains("终身"));
        assertTrue(dictionarySql.contains("每年"));
        assertTrue(dictionarySql.contains("每月"));
        assertTrue(dictionarySql.contains("每周"));
        assertTrue(dictionarySql.contains("每天"));
        List.of("'LIFETIME'", "'YEAR'", "'MONTH'", "'WEEK'", "'DAY'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少限购周期值：" + value));
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
                            + "db/changelog/releases/1.0.0/008-sku-purchase-limit-period-dictionary.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 SKU 限购周期数据字典 Liquibase 变更集");
    }
}
