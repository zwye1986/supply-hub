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
 * 开票抬头类型字典必须与供应链 {@code InvoiceTitleType} 封闭枚举值一致，且可安全重复执行。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#31-抬头类型数据字典-supply_chain_invoice_title_type}。
 */
class InvoiceTitleTypeDictionaryLiquibaseTest {

    private static final String DICT_TYPE = "supply_chain_invoice_title_type";
    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/004-invoice-title-type-dictionary.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersInvoiceTitleTypeDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 004-invoice-title-type-dictionary"));
        assertTrue(releaseChangelog.contains("path: 004-invoice-title-type-dictionary.sql"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
    }

    @Test
    void scriptDefinesClosedInvoiceTitleTypeValues() {
        assertTrue(dictionarySql.contains(DICT_TYPE));
        List.of("'ENTERPRISE'", "'PERSONAL'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少开票抬头类型：" + value));
        List.of("'enterprise'", "'personal'", "'company'", "'1'", "'2'")
                .forEach(value -> assertFalse(dictionarySql.contains(value), () -> "不得写入非法或私有码：" + value));
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
                            + "db/changelog/releases/1.0.0/004-invoice-title-type-dictionary.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到开票抬头类型数据字典 Liquibase 变更集");
    }
}
