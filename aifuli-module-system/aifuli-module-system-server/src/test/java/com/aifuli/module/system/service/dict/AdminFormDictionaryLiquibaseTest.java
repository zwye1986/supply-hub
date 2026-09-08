package com.aifuli.module.system.service.dict;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * system Liquibase `002-admin-form-dictionaries` 结构与幂等写法校验。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#51-system}。
 */
class AdminFormDictionaryLiquibaseTest {

    private static final List<String> DICT_TYPES = List.of(
            "supply_chain_sellable_category",
            "supply_chain_product_type",
            "supply_chain_product_classification",
            "supply_chain_distribution_level",
            "supply_chain_cooperation_mode",
            "supply_chain_distributor_type",
            "supply_chain_supply_preference",
            "supply_chain_buyer_status");

    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/002-admin-form-dictionaries.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 002-admin-form-dictionaries"));
        assertTrue(releaseChangelog.contains("002-admin-form-dictionaries.sql"));
    }

    @Test
    void scriptDefinesAllFormDictionaryTypes() {
        for (String dictType : DICT_TYPES) {
            assertTrue(dictionarySql.contains(dictType), () -> "缺少字典类型：" + dictType);
        }
    }

    @Test
    void valuesMatchClosedEnumNamesNotFrontendMockValues() {
        List.of("'EQUITY'", "'PHYSICAL'", "'MOVIE'", "'JD'", "'DINING'", "'TAKEOUT'",
                        "'SUPERMARKET'", "'HEMA'", "'RTMART'", "'LIFE'",
                        "'EQUITY_PRODUCT'", "'VIP'",
                        "'ELECTRONICS'", "'VIRTUAL_CARD'", "'MOVIE_TICKET'",
                        "'DIRECT'", "'AGENT'", "'CHANNEL'",
                        "'ENTERPRISE'", "'PERSONAL'", "'PLATFORM'",
                        "'STABLE'", "'PRICE'", "'COMPREHENSIVE'",
                        "'ACTIVE'", "'SUSPENDED'", "'BLACKLIST'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少枚举值：" + value));
        List.of("'equity'", "'direct'", "'enterprise'", "'physical'")
                .forEach(mock -> assertTrue(!dictionarySql.contains(mock), () -> "不得写入前端 mock 小写码：" + mock));
    }

    @Test
    void insertsAreIdempotentAndUpdatesAreScoped() {
        String normalized = dictionarySql.toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("where not exists"));
        assertTrue(dictionarySql.contains("CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)"));
        assertTrue(dictionarySql.contains(
                "CAST(existing.dict_type AS BINARY) = CAST(desired.dict_type AS BINARY)"));
    }

    @Test
    void scriptContainsNoDestructiveStatements() {
        String normalized = dictionarySql.toLowerCase(Locale.ROOT);
        assertFalse(normalized.contains("delete from"), "字典迁移脚本不得包含 DELETE");
        assertFalse(normalized.contains("drop table"), "字典迁移脚本不得包含 DROP TABLE");
        assertFalse(normalized.contains("truncate"), "字典迁移脚本不得包含 TRUNCATE");
    }

    private static Path findRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                            + "db/changelog/releases/1.0.0/001-supplier-form-dictionaries.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repo root not found");
    }
}
