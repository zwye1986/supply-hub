package com.aifuli.module.supplychain.tradecore.supply.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 供应商管理端本期开放枚举。设计见规格 15.5。
 */
class SupplierAdminEnumsTest {

    @Test
    void supplyModeAndPriceModeRejectUnknownCodes() {
        assertThat(SupplyMode.fromCode("API_DIRECT")).isEqualTo(SupplyMode.API_DIRECT);
        assertThat(SupplyMode.fromCode("DROP_SHIP")).isEqualTo(SupplyMode.DROP_SHIP);
        assertThatThrownBy(() -> SupplyMode.fromCode("WAREHOUSE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(OfferPriceMode.fromCode("FIXED")).isEqualTo(OfferPriceMode.FIXED);
        assertThatThrownBy(() -> OfferPriceMode.fromCode("TIER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DeliveryRegionType.fromCode("NATIONWIDE")).isEqualTo(DeliveryRegionType.NATIONWIDE);
        assertThatThrownBy(() -> DeliveryRegionType.fromCode("PROVINCE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
