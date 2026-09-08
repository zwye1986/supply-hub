package com.aifuli.module.supplychain.tradecore.supply.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplyModeTest {

    @Test
    void acceptsFiveClosedModesAndRejectsLegacyMockCodes() {
        assertThat(SupplyMode.fromCode("API_DIRECT")).isEqualTo(SupplyMode.API_DIRECT);
        assertThat(SupplyMode.fromCode("DROP_SHIP")).isEqualTo(SupplyMode.DROP_SHIP);
        assertThat(SupplyMode.fromCode("BATCH")).isEqualTo(SupplyMode.BATCH);
        assertThat(SupplyMode.fromCode("PICKUP")).isEqualTo(SupplyMode.PICKUP);
        assertThat(SupplyMode.fromCode("H5")).isEqualTo(SupplyMode.H5);
        assertThatThrownBy(() -> SupplyMode.fromCode("dropshipping"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupplyMode.fromCode("api"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
