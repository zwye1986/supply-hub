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
 * 供应商履约技术配置菜单 Liquibase 变更集测试。
 * 验证 system-server 迁移注册、菜单属性以及幂等、非破坏性维护约束。
 * 设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#81-技术菜单a1新顶级菜单}。
 */
class SupplierFulfillmentConfigMenuLiquibaseTest {

    private static String menuSql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        menuSql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/013-supplier-fulfillment-config-menu.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersMenuMigration() {
        assertTrue(releaseChangelog.contains("id: 013-supplier-fulfillment-config-menu"));
        assertTrue(releaseChangelog.contains("path: 013-supplier-fulfillment-config-menu.sql"));
        assertTrue(releaseChangelog.contains("author: supply-chain"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
    }

    @Test
    void scriptDefinesSupplierFulfillmentConfigMenu() {
        assertTrue(menuSql.contains("供应商履约技术配置"));
        assertTrue(menuSql.contains("supply-chain:admin"));
        assertTrue(menuSql.contains("fulfillment-config"));
        assertTrue(menuSql.contains("supplier/config/index"));
        assertTrue(menuSql.contains("SupplierFulfillmentConfig"));
        assertTrue(menuSql.contains("ep:setting"));
    }

    @Test
    void scriptIsIdempotentAndNonDestructive() {
        String normalized = menuSql.toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("not exists"));
        assertTrue(normalized.contains("where"));
        assertTrue(menuSql.contains("UPDATE system_menu"));
        assertFalse(normalized.contains("delete from"));
        assertFalse(normalized.contains("drop table"));
        assertFalse(normalized.contains("truncate"));
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                            + "db/changelog/releases/1.0.0/013-supplier-fulfillment-config-menu.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到供应商履约技术配置菜单 Liquibase 变更集");
    }
}
