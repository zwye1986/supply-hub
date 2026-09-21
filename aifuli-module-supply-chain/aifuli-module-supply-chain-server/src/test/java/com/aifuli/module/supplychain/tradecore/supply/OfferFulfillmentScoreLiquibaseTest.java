package com.aifuli.module.supplychain.tradecore.supply;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 042 只追加 Offer 身份评分列，禁止回改历史 changeSet。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md#3-存储}。
 */
class OfferFulfillmentScoreLiquibaseTest {

    private static final String STABILITY_COMMENT = "稳定性评分，人工录入，0-100";
    private static final String TIMELINESS_COMMENT = "时效性评分，人工录入，0-100";

    private static String migrationSql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path releaseDir = findRepoRoot().resolve(
                "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0");
        migrationSql = Files.readString(releaseDir.resolve("042-offer-fulfillment-score.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(releaseDir.resolve("db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersMigration() {
        assertThat(releaseChangelog)
                .contains("id: 042-offer-fulfillment-score")
                .contains("path: 042-offer-fulfillment-score.sql");
    }

    @Test
    void addsPairedNullableScoresWithCheck() {
        assertThat(migrationSql)
                .contains("ADD COLUMN stability_score TINYINT NULL COMMENT '" + STABILITY_COMMENT + "'")
                .contains("ADD COLUMN timeliness_score TINYINT NULL COMMENT '" + TIMELINESS_COMMENT + "'")
                .contains("AFTER draft_min_quantity")
                .contains("ADD CONSTRAINT ck_sc_offer_fulfillment_score CHECK")
                .contains("stability_score IS NULL AND timeliness_score IS NULL")
                .contains("stability_score BETWEEN 0 AND 100 AND timeliness_score BETWEEN 0 AND 100")
                .doesNotContain("primary_capability_code");
    }

    private static Path findRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                            + "db/changelog/releases/1.0.0/023-admin-invoice.sql");
            if (Files.exists(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repo root not found");
    }
}
