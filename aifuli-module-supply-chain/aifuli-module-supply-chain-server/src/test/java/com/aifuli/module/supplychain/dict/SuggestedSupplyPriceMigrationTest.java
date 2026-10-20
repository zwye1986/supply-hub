package com.aifuli.module.supplychain.dict;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** 动态建议价必须是独立增量，不修改已发布018。设计见2026-10-19建议价设计§4。 */
class SuggestedSupplyPriceMigrationTest {
    @Test
    void registersIndependentStateAndNullableSnapshotsAndSupplyDictionary() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("aifuli-module-system"))) root = root.getParent();
        String supply = "aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/";
        String system = "aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/releases/1.0.0/";
        Path migration = root.resolve(supply + "068-sku-suggested-price-state.sql");
        assertThat(migration).exists();
        assertThat(Files.readString(migration)).contains("sc_sku_suggested_price_state", "effective_price_cents", "last_valid_price_cents", "rule_digest", "DEPENDENCY_MISSING", "CALCULATION_FAILED");
        assertThat(Files.readString(root.resolve(supply + "db.changelog-1.0.0.yaml")))
                .contains("068-sku-suggested-price-state.sql");
        Path dictionary = root.resolve(system + "019-suggested-sale-price-supply-base.sql");
        assertThat(dictionary).exists();
        assertThat(Files.readString(dictionary)).contains("'SUPPLY_PRICE'", "'供应价'", "WHERE NOT EXISTS");
        assertThat(Files.readString(root.resolve(system + "db.changelog-1.0.0.yaml")))
                .contains("019-suggested-sale-price-supply-base.sql");
        assertThat(Files.readString(root.resolve(system + "018-suggested-sale-price-base-dictionary.sql")))
                .doesNotContain("'SUPPLY_PRICE'");
    }
}
