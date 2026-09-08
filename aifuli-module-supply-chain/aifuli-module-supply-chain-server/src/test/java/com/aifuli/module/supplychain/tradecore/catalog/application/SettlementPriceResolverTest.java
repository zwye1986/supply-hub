package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.L3AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.L2_OVERRIDE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementPriceResolverTest {

    private final SettlementPriceResolver resolver = new SettlementPriceResolver();

    @Test
    void prefersL3ThenManualL2ThenTemplateL2ThenL1() {
        assertThat(resolver.resolve(30L, 20L, 15L, 10L))
                .isEqualTo(new SettlementPriceResolver.ResolvedPrice(30L, PriceSource.L3));
        assertThat(resolver.resolve(null, 20L, 15L, 10L))
                .isEqualTo(new SettlementPriceResolver.ResolvedPrice(20L, PriceSource.L2));
        assertThat(resolver.resolve(null, null, 15L, 10L))
                .isEqualTo(new SettlementPriceResolver.ResolvedPrice(15L, PriceSource.L2));
        assertThat(resolver.resolve(null, null, null, 10L))
                .isEqualTo(new SettlementPriceResolver.ResolvedPrice(10L, PriceSource.L1));
        assertThatThrownBy(() -> resolver.resolve(null, null, null, null))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PLATFORM_SETTLEMENT_PRICE_MISSING.getCode());
    }

    @Test
    void equalL1UsesBSettlementCentsAndCustomRounds() {
        assertThat(resolver.applyL2(L2AdjustMode.EQUAL_L1, 999L, null, null)).isEqualTo(999L);
        assertThat(resolver.applyL2(L2AdjustMode.CUSTOM, 1000L, 80, 50L)).isEqualTo(850L);
        assertThat(resolver.applyL2(L2AdjustMode.CUSTOM, 101L, 50, 0L)).isEqualTo(51L);
        assertThatThrownBy(() -> resolver.applyL2(L2AdjustMode.CUSTOM, 100L, 0, 0L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(L2_OVERRIDE_INVALID.getCode());
        assertThat(resolver.applyL3(L3AdjustMode.FOLLOW_L2, 100L, null, null)).isNull();
        assertThat(resolver.applyL3(L3AdjustMode.EQUAL_L1, 777L, null, null)).isEqualTo(777L);
    }

    @Test
    void followTemplateClearsOverride() {
        assertThat(resolver.applyL2(L2AdjustMode.FOLLOW_TEMPLATE, 1000L, 120, 1L)).isNull();
    }
}
