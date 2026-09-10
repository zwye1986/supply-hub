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
 * 项目列表筛选字典必须与 PRD 8.3.1 及供应链封闭枚举值一致，并可安全重复执行。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#421-项目列表数据字典}。
 */
class ProjectListDictionaryLiquibaseTest {

    private static final String PROJECT_TYPE_DICT = "supply_chain_project_type";
    private static final String PROJECT_STATUS_DICT = "supply_chain_project_status";
    private static String dictionarySql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/005-project-list-dictionaries.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersProjectListDictionaryMigration() {
        assertTrue(releaseChangelog.contains("id: 005-project-list-dictionaries"));
        assertTrue(releaseChangelog.contains("path: 005-project-list-dictionaries.sql"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
    }

    @Test
    void scriptDefinesPrdProjectTypesAndStatuses() {
        assertTrue(dictionarySql.contains(PROJECT_TYPE_DICT));
        assertTrue(dictionarySql.contains(PROJECT_STATUS_DICT));
        List.of("'HOLIDAY_BENEFIT'", "'POINTS_REDEMPTION'", "'EMPLOYEE_BENEFIT'",
                        "'CUSTOMER_REWARD'", "'FLEXIBLE_BENEFIT'", "'OTHER'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少 PRD 项目类型：" + value));
        List.of("'DRAFT'", "'ACTIVE'", "'CLOSED'")
                .forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少 PRD 项目状态：" + value));
        List.of("'WELFARE'", "'BENEFIT'", "'CAMPAIGN'", "'SUBSIDY'", "'GROUP'",
                        "'SUSPENDED'", "'ARCHIVED'")
                .forEach(value -> assertFalse(dictionarySql.contains(value), () -> "不得暴露非 PRD 筛选值：" + value));
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
                            + "db/changelog/releases/1.0.0/005-project-list-dictionaries.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到项目列表数据字典 Liquibase 变更集");
    }
}
