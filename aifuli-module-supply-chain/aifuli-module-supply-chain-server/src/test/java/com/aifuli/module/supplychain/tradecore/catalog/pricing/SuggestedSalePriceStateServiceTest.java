package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.SkuSuggestedPriceStateMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SuggestedSalePriceStateServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026,10,19,12,0);
    private final SkuSuggestedPriceStateMapper mapper = mock(SkuSuggestedPriceStateMapper.class);
    private final SuggestedSalePriceStateService service = new SuggestedSalePriceStateService(mapper);
    private PlatformSkuDO sku() {
        return PlatformSkuDO.builder().id(1L).marketPrice(10000L).suggestedSalePrice(0L)
                .suggestedSalePriceRuleJson(new SuggestedSalePriceRule(SuggestedSalePriceRule.Mode.CUSTOM,
                        SuggestedSalePriceRule.Base.SUPPLY_PRICE, new BigDecimal("110"),
                        SuggestedSalePriceRule.Operator.ADD, BigDecimal.ONE, null, null)).build();
    }
    @Test void createsPendingRowWithoutManufacturingZero() {
        when(mapper.insert(any(SkuSuggestedPriceStateDO.class))).thenReturn(1);
        var sku = sku();
        var result = service.refresh(sku, SuggestedSalePriceResolver.evaluate(sku, null, null, NOW), null, NOW);
        assertThat(result.getEffectivePriceCents()).isNull();
        assertThat(result.getLastValidPriceCents()).isNull();
        assertThat(result.getRevision()).isEqualTo(1L);
    }
    @Test void invalidationClearsCurrentAmountButKeepsLastSuccess() {
        var sku = sku();
        var old = row(sku, 8900L);
        when(mapper.selectById(1L)).thenReturn(old);
        when(mapper.updateById(any(SkuSuggestedPriceStateDO.class))).thenReturn(1);
        var result = service.refresh(sku, SuggestedSalePriceResolver.evaluate(sku, null, old, NOW), null, NOW);
        assertThat(result.getEffectivePriceCents()).isNull();
        assertThat(result.getLastValidPriceCents()).isEqualTo(8900L);
        assertThat(result.getCalculationStatus()).isEqualTo("DEPENDENCY_MISSING");
    }
    @Test void unchangedFactsDoNotWriteOrIncrementRevision() {
        var sku = sku();
        var old = row(sku, 8900L);
        when(mapper.selectById(1L)).thenReturn(old);
        var result = service.refresh(sku, SuggestedSalePriceResolver.evaluate(sku, 8000L, old, NOW), null, NOW);
        assertThat(result.getRevision()).isEqualTo(7L);
        verify(mapper, never()).updateById(any(SkuSuggestedPriceStateDO.class));
    }
    @Test void recoveryExplicitlyRemovesOldReason() {
        var sku = sku(); var old = row(sku, 8900L);
        old.setCalculationStatus("DEPENDENCY_MISSING"); old.setEffectivePriceCents(null);
        old.setUnavailableReason("EFFECTIVE_SUPPLY_PRICE_MISSING"); old.setBasePriceSnapshotCents(null);
        when(mapper.selectById(1L)).thenReturn(old);
        when(mapper.updateById(any(SkuSuggestedPriceStateDO.class))).thenReturn(1);
        var result = service.refresh(sku, SuggestedSalePriceResolver.evaluate(sku, 9000L, old, NOW), null, NOW);
        assertThat(result.getUnavailableReason()).isNull();
        assertThat(result.getEffectivePriceCents()).isEqualTo(10000L);
    }
    private SkuSuggestedPriceStateDO row(PlatformSkuDO sku, long price) {
        return SkuSuggestedPriceStateDO.builder().platformSkuId(1L).effectivePriceCents(price)
                .basePriceSnapshotCents(8000L).lastValidPriceCents(price).lastValidCalculatedAt(NOW.minusHours(1))
                .ruleDigest(SuggestedSalePriceResolver.ruleDigest(sku.getSuggestedSalePriceRuleJson()))
                .calculationStatus("READY").calculatedAt(NOW.minusHours(1)).revision(7L).build();
    }
}
