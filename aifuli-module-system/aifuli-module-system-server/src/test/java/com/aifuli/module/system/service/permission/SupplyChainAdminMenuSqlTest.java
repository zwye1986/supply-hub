package com.aifuli.module.system.service.permission;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 供应链菜单 SQL 必须可重复执行，且不得按 permission 全局改写其它应用菜单。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#83-旧-admin-兼容角色与脚本}。
 */
class SupplyChainAdminMenuSqlTest {

    private static String schemaSql;
    private static String menuSql;

    @BeforeAll
    static void loadScripts() throws IOException {
        Path repoRoot = findRepoRoot();
        schemaSql = Files.readString(repoRoot.resolve(
                "script/deploy/aifuli-test/sql/system-menu-application-schema.sql"), StandardCharsets.UTF_8);
        menuSql = Files.readString(repoRoot.resolve(
                "script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql"), StandardCharsets.UTF_8);
    }

    @Test
    void schemaScriptAddsCompatibleApplicationColumn() {
        String normalized = schemaSql.toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("information_schema.columns"));
        assertTrue(normalized.contains("prepare application_code_statement"));
        assertTrue(normalized.contains("add column application_code"));
        assertFalse(normalized.contains("add column if not exists"));
        assertTrue(normalized.contains("default ''mall-admin''"));
    }

    @Test
    void menuScriptInsertsAreIdempotentAndScopedToApplication() {
        assertTrue(menuSql.contains("WHERE NOT EXISTS"));
        assertTrue(menuSql.contains("BINARY application_code = BINARY @sc_app"));
        assertTrue(menuSql.contains("supply-chain:supplier:query"));
        assertTrue(menuSql.contains("supply-chain:buyer:query"));
        assertTrue(menuSql.contains("supply-chain:project:query"));
        assertTrue(menuSql.contains("supply-chain:order:query"));
        assertTrue(menuSql.contains("supplier/manage/index"));
        assertTrue(menuSql.contains("supplier/distribution/distributor/index"));
        assertTrue(menuSql.contains("supplier/distribution/project/index"));
        assertTrue(menuSql.contains("supplier/order/index"));
    }

    @Test
    void buttonUpdatesDoNotTouchOtherApplications() {
        assertTrue(menuSql.contains("AND BINARY existing.permission = BINARY actions.permission"));
        assertTrue(menuSql.contains("ON BINARY actions.permission = BINARY menu.permission"));
        assertTrue(menuSql.contains("WHERE BINARY menu.application_code = BINARY @sc_app AND menu.deleted = b'0'"));
        assertFalse(menuSql.contains("UPDATE system_role_menu"));
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到供应链菜单 SQL 脚本");
    }
}
