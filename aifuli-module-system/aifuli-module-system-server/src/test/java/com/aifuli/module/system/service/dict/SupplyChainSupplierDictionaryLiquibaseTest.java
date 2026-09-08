package com.aifuli.module.system.service.dict;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 供应商表单字典脚本必须可重复执行，字典值必须与供应链封闭枚举契约一致。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#521-新增供应商数据字典}。
 */
class SupplyChainSupplierDictionaryLiquibaseTest {

    private static final List<String> DICT_TYPES = List.of(
            "supply_chain_delivery_region_type",
            "supply_chain_shipping_sla_code",
            "supply_chain_aftersales_supported",
            "supply_chain_settlement_method",
            "supply_chain_integration_mode",
            "supply_chain_supplier_status"
    );

    private static String dictionarySql;
    private static String masterChangelog;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path repoRoot = findRepoRoot();
        dictionarySql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/001-supplier-form-dictionaries.sql"),
                StandardCharsets.UTF_8);
        masterChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/db.changelog-master.yaml"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersDictionaryMigration() {
        assertTrue(masterChangelog.contains("db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml"));
        assertTrue(releaseChangelog.contains("id: 001-supplier-form-dictionaries"));
        assertTrue(releaseChangelog.contains("author: supply-chain"));
        assertTrue(releaseChangelog.contains("runInTransaction: true"));
        assertTrue(releaseChangelog.contains("path: 001-supplier-form-dictionaries.sql"));
        assertTrue(releaseChangelog.contains("relativeToChangelogFile: true"));
    }

    @Test
    void scriptDefinesAllSupplierFormDictionaryTypes() {
        for (String dictType : DICT_TYPES) {
            assertTrue(dictionarySql.contains(dictType), () -> "缺少供应商表单字典类型：" + dictType);
        }
        assertEquals(6, DICT_TYPES.size());
    }

    @Test
    void valuesFollowBackendContractInsteadOfFrontendMockValues() {
        List.of(
                "'NATIONWIDE'", "'DESIGNATED'",
                "'SAME_DAY'", "'NEXT_DAY'", "'HOURS_48'", "'HOURS_72'", "'CUSTOM'",
                "'true'", "'false'",
                "'DAILY'", "'WEEKLY'", "'MONTHLY'", "'QUARTERLY'", "'PREPAID'",
                "'API'", "'H5'", "'MANUAL'",
                "'ACTIVE'", "'SUSPENDED'", "'BLACKLIST'"
        ).forEach(value -> assertTrue(dictionarySql.contains(value), () -> "缺少后端契约值：" + value));

        List.of("'nationwide'", "'specified'", "'same_day'", "'next_day'", "'48h'", "'72h'", "'ARCHIVED'")
                .forEach(value -> assertFalse(dictionarySql.contains(value), () -> "不应写入前端 mock 或归档值：" + value));
    }

    @Test
    void insertsAreIdempotentAndUpdatesAreScoped() {
        String normalized = dictionarySql.toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("where not exists"));
        assertTrue(dictionarySql.contains("CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)"));
        assertTrue(dictionarySql.contains("CAST(existing.dict_type AS BINARY) = CAST(desired.dict_type AS BINARY)"));
        assertTrue(dictionarySql.contains("CAST(existing.value AS BINARY) = CAST(desired.value AS BINARY)"));
        assertTrue(dictionarySql.contains("CAST(desired.type AS BINARY) = CAST(dict_type.type AS BINARY)"));
        assertTrue(dictionarySql.contains("CAST(desired.dict_type AS BINARY) = CAST(dict_data.dict_type AS BINARY)"));
        assertTrue(dictionarySql.contains("CAST(desired.value AS BINARY) = CAST(dict_data.value AS BINARY)"));
        assertFalse(normalized.contains("delete from system_dict"));
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "aifuli-module-system/aifuli-module-system-server/src/main/resources/"
                            + "db/changelog/releases/1.0.0/001-supplier-form-dictionaries.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到供应商表单数据字典 Liquibase 变更集");
    }
}
