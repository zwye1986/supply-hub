package com.aifuli.module.supplychain.dict;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目类型迁移必须扩容字段并同时支持 PRD 当前值与历史兼容值。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#421-项目列表数据字典}。
 */
class ProjectTypePrdValuesLiquibaseTest {

    private static String migrationSql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        migrationSql = Files.readString(repoRoot.resolve(
                "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/025-project-type-prd-values.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersMigration() {
        assertTrue(releaseChangelog.contains("id: 025-project-type-prd-values"));
        assertTrue(releaseChangelog.contains("path: 025-project-type-prd-values.sql"));
    }

    @Test
    void widensProjectTypeAndAcceptsPrdValues() {
        assertTrue(migrationSql.contains("MODIFY COLUMN project_type VARCHAR(32) NULL"));
        List.of("'HOLIDAY_BENEFIT'", "'POINTS_REDEMPTION'", "'EMPLOYEE_BENEFIT'",
                        "'CUSTOMER_REWARD'", "'FLEXIBLE_BENEFIT'", "'OTHER'")
                .forEach(value -> assertTrue(migrationSql.contains(value), () -> "缺少 PRD 项目类型：" + value));
    }

    @Test
    void keepsLegacyValuesDuringCompatibilityWindow() {
        List.of("'WELFARE'", "'BENEFIT'", "'CAMPAIGN'", "'SUBSIDY'", "'GROUP'")
                .forEach(value -> assertTrue(migrationSql.contains(value), () -> "缺少历史兼容值：" + value));
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                            + "db/changelog/releases/1.0.0/025-project-type-prd-values.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到项目类型 PRD 值 Liquibase 变更集");
    }
}
