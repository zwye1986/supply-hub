package com.aifuli.module.supplychain.dict;

import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKU 销售建议价基准来源字典必须由 system-server 增量迁移，并与部署热更脚本保持一致；领域历史基数不作为新选项。
 * 设计见
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#531-销售建议价基准来源字典与历史规则兼容2026-10-18}。
 */
class SuggestedSalePriceBaseDictionaryLiquibaseTest {

    private static final String DICT_TYPE = "supply_chain_suggested_sale_price_base";
    private static final Pattern BASE_ROW = Pattern.compile(
            "(?:SELECT|UNION ALL SELECT)\\s+\\d+(?:\\s+sort)?,\\s*'[^']+'(?:\\s+label)?,\\s*'([A-Z_]+)'(?:\\s+value)?,\\s*'"
                    + DICT_TYPE + "'",
            Pattern.MULTILINE);

    private static String migrationSql;
    private static String hotfixSql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScripts() throws IOException {
        Path repoRoot = findRepoRoot();
        assertThat(Files.exists(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/"
                        + "releases/1.0.0/018-suggested-sale-price-base-dictionary.sql")))
                .as("system 字典增量迁移必须存在").isTrue();
        migrationSql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/"
                        + "releases/1.0.0/018-suggested-sale-price-base-dictionary.sql"),
                StandardCharsets.UTF_8);
        hotfixSql = Files.readString(repoRoot.resolve(
                "script/deploy/aifuli-test/sql/system-supply-chain-suggested-sale-price-base-dict.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/"
                        + "releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void systemChangelogRegistersDictionaryMigration() {
        assertThat(releaseChangelog)
                .contains("id: 018-suggested-sale-price-base-dictionary")
                .contains("path: 018-suggested-sale-price-base-dictionary.sql");
    }

    @Test
    void dictionaryContainsOnlyMarketPriceForNewSelections() {
        Set<String> expectedBases = Set.of(SuggestedSalePriceRule.Base.MARKET_PRICE.name());

        assertThat(extractBases(migrationSql)).isEqualTo(expectedBases);
        assertThat(extractBases(hotfixSql)).isEqualTo(expectedBases);
        assertThat(migrationSql).contains("'市场价'").doesNotContain("'SUGGESTED_SALE_PRICE'");
        assertThat(hotfixSql).contains("'市场价'").doesNotContain("'SUGGESTED_SALE_PRICE'");
    }

    @Test
    void migrationAndHotfixAreIdempotentAndNonDestructive() {
        for (String script : new String[]{migrationSql, hotfixSql}) {
            assertThat(script)
                    .contains("WHERE NOT EXISTS")
                    .contains("UPDATE system_dict_type")
                    .contains("UPDATE system_dict_data")
                    .contains("existing.deleted = b'0'")
                    .doesNotContain("DELETE FROM system_dict_type")
                    .doesNotContain("DELETE FROM system_dict_data");
        }
    }

    private static Set<String> extractBases(String script) {
        return BASE_ROW.matcher(script).results()
                .map(result -> result.group(1))
                .collect(Collectors.toSet());
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/"
                            + "releases/1.0.0/db.changelog-1.0.0.yaml");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 system-server Liquibase 主清单");
    }
}
