package com.aifuli.module.supplychain.dict;

import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKU 销售建议价运算符字典必须由 system-server 增量迁移，并与部署热更脚本和领域枚举保持一致。
 * 设计见
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#53-商品中心数据字典与配置变更}。
 */
class SuggestedSalePriceOperatorDictionaryLiquibaseTest {

    private static final String DICT_TYPE = "supply_chain_suggested_sale_price_operator";
    private static final Pattern OPERATOR_ROW = Pattern.compile(
            "(?:SELECT|UNION ALL SELECT)\\s+\\d+(?:\\s+sort)?,\\s*'[^']+'(?:\\s+label)?,\\s*'([A-Z_]+)'(?:\\s+value)?,\\s*'"
                    + DICT_TYPE + "'",
            Pattern.MULTILINE);

    private static String migrationSql;
    private static String hotfixSql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScripts() throws IOException {
        Path repoRoot = findRepoRoot();
        migrationSql = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/"
                        + "releases/1.0.0/011-suggested-sale-price-operator-dictionary.sql"),
                StandardCharsets.UTF_8);
        hotfixSql = Files.readString(repoRoot.resolve(
                "script/deploy/aifuli-test/sql/system-supply-chain-suggested-sale-price-operator-dict.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/"
                        + "releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void systemChangelogRegistersDictionaryMigration() {
        assertThat(releaseChangelog)
                .contains("id: 011-suggested-sale-price-operator-dictionary")
                .contains("path: 011-suggested-sale-price-operator-dictionary.sql");
    }

    @Test
    void dictionaryValuesMatchDomainOperatorsExactly() {
        Set<String> expectedOperators = Arrays.stream(SuggestedSalePriceRule.Operator.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(extractOperators(migrationSql)).isEqualTo(expectedOperators);
        assertThat(extractOperators(hotfixSql)).isEqualTo(expectedOperators);
        assertThat(migrationSql).contains("'加'", "'减'", "'乘'", "'除'");
        assertThat(hotfixSql).contains("'加'", "'减'", "'乘'", "'除'");
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

    private static Set<String> extractOperators(String script) {
        return OPERATOR_ROW.matcher(script).results()
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
