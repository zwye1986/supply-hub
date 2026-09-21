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
    private static final String STABILITY_DEFAULT_COMMENT = "稳定性评分，人工录入，0-100，默认80";
    private static final String TIMELINESS_DEFAULT_COMMENT = "时效性评分，人工录入，0-100，默认80";

    private static String migrationSql;
    private static String defaultMigrationSql;
    private static String releaseChangelog;

    @BeforeAll
    static void loadScript() throws IOException {
        Path releaseDir = findRepoRoot().resolve(
                "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/"
                        + "db/changelog/releases/1.0.0");
        migrationSql = Files.readString(releaseDir.resolve("042-offer-fulfillment-score.sql"),
                StandardCharsets.UTF_8);
        defaultMigrationSql = Files.readString(releaseDir.resolve("043-offer-fulfillment-score-default.sql"),
                StandardCharsets.UTF_8);
        releaseChangelog = Files.readString(releaseDir.resolve("db.changelog-1.0.0.yaml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void changelogRegistersMigration() {
        assertThat(releaseChangelog)
                .contains("id: 042-offer-fulfillment-score")
                .contains("path: 042-offer-fulfillment-score.sql")
                .contains("id: 043-offer-fulfillment-score-default")
                .contains("path: 043-offer-fulfillment-score-default.sql");
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

    @Test
    void backfillsNullScoresToDefaultEighty() {
        assertThat(defaultMigrationSql)
                .contains("SET stability_score = 80, timeliness_score = 80")
                .contains("stability_score IS NULL OR timeliness_score IS NULL")
                .contains("DROP CHECK ck_sc_offer_fulfillment_score")
                .contains("MODIFY COLUMN stability_score TINYINT NOT NULL DEFAULT 80 COMMENT '"
                        + STABILITY_DEFAULT_COMMENT + "'")
                .contains("MODIFY COLUMN timeliness_score TINYINT NOT NULL DEFAULT 80 COMMENT '"
                        + TIMELINESS_DEFAULT_COMMENT + "'")
                .contains("ADD CONSTRAINT ck_sc_offer_fulfillment_score CHECK")
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
