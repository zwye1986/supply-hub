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
 * 供应链结算展示税率数据字典 Liquibase 变更集测试。
 * 验证变更集注册、字典类型及键值定义、以及幂等与非破坏性约束。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性}。
 */
class SupplyChainTaxRateDictionaryLiquibaseTest {

    private static final String TAX_RATE_DICT = "supply_chain_tax_rate";
    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/009-supply-chain-tax-rate-dictionary.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersTaxRateDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 009-supply-chain-tax-rate-dictionary"));
        assertTrue(releaseChangelog.contains("path: 009-supply-chain-tax-rate-dictionary.sql"));
        assertTrue(releaseChangelog.contains("author: supply-chain"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
    }

    @Test
    void scriptDefinesTaxRateDictionaryAndValues() {
        assertTrue(dictionarySql.contains(TAX_RATE_DICT));
        assertTrue(dictionarySql.contains("供应链结算展示税率"));
        assertTrue(dictionarySql.contains("6%"));
        assertTrue(dictionarySql.contains("9%"));
        assertTrue(dictionarySql.contains("13%"));
        assertTrue(dictionarySql.contains("不征税"));

        List.of("'RATE_6'", "'RATE_9'", "'RATE_13'", "'NON_TAXABLE'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少税率字典值：" + value));

        List.of("'0.06'", "'0.09'", "'0.13'", "'0'")
                .forEach(remark -> assertTrue(dictionarySql.contains(remark), () -> "缺少十进制税率备注：" + remark));
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
                            + "db/changelog/releases/1.0.0/009-supply-chain-tax-rate-dictionary.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到结算展示税率数据字典 Liquibase 变更集");
    }
}
