package com.aifuli.module.supplychain.dict;

import com.aifuli.module.supplychain.tradecore.supply.domain.OfferPriceMode;
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
 * 合作商品供应价模式字典必须由 system-server 自动迁移，并与部署热更脚本和领域枚举保持一致。
 * 设计见
 * {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#25-供应价模式-offerpricemode}。
 */
class OfferPriceModeDictionaryLiquibaseTest {

    private static final String DICT_TYPE = "supply_chain_offer_price_mode";
    private static final Pattern MODE_ROW = Pattern.compile(
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
                        + "releases/1.0.0/012-offer-price-mode-dictionary.sql"),
                StandardCharsets.UTF_8);
        hotfixSql = Files.readString(repoRoot.resolve(
                "script/deploy/aifuli-test/sql/system-supply-chain-offer-price-mode-dict.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(repoRoot.resolve(
                "aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/"
                        + "releases/1.0.0/db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void systemChangelogRegistersDictionaryMigration() {
        assertThat(releaseChangelog)
                .contains("id: 012-offer-price-mode-dictionary")
                .contains("path: 012-offer-price-mode-dictionary.sql");
    }

    @Test
    void dictionaryValuesMatchDomainModesExactly() {
        Set<String> expectedModes = Arrays.stream(OfferPriceMode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(extractModes(migrationSql)).isEqualTo(expectedModes);
        assertThat(extractModes(hotfixSql)).isEqualTo(expectedModes);
        for (OfferPriceMode mode : OfferPriceMode.values()) {
            assertThat(migrationSql).contains("'" + mode.getChineseName() + "'");
            assertThat(hotfixSql).contains("'" + mode.getChineseName() + "'");
        }
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

    private static Set<String> extractModes(String script) {
        return MODE_ROW.matcher(script).results()
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
