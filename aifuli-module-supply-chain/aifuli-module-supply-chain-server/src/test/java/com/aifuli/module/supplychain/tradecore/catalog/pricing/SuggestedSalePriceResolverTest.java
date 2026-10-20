package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.SkuSuggestedPriceStateDO;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

/** 当前有效值与历史参考严格分离；不访问数据库的解析事实门禁。 */
class SuggestedSalePriceResolverTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026,10,19,12,0);
    private PlatformSkuDO sku(String operand) {
        return PlatformSkuDO.builder().id(1L).marketPrice(10000L).suggestedSalePrice(99999L)
                .suggestedSalePriceRuleJson(new SuggestedSalePriceRule(SuggestedSalePriceRule.Mode.CUSTOM,
                        SuggestedSalePriceRule.Base.SUPPLY_PRICE, new BigDecimal("110"),
                        SuggestedSalePriceRule.Operator.ADD, new BigDecimal(operand), null, 77777L)).build();
    }

    @Test void ignoresCompatibilityAmountAndClientSnapshot() {
        var result = SuggestedSalePriceResolver.evaluate(sku("1"), 8000L, null, NOW);
        assertThat(result.amountCents()).isEqualTo(8900L);
        assertThat(result.basePriceSnapshotCents()).isEqualTo(8000L);
        assertThat(result.status()).isEqualTo(SuggestedPriceStatus.READY);
        assertThat(result.unavailableReason()).isNull();
    }

    @Test void missingSupplyDoesNotLeakCompatibilityOrLastAmount() {
        var old = SkuSuggestedPriceStateDO.builder().lastValidPriceCents(8900L)
                .lastValidCalculatedAt(NOW.minusHours(1)).build();
        var result = SuggestedSalePriceResolver.evaluate(sku("1"), null, old, NOW);
        assertThat(result.amountCents()).isNull();
        assertThat(result.lastValidPriceCents()).isEqualTo(8900L);
        assertThat(result.status()).isEqualTo(SuggestedPriceStatus.DEPENDENCY_MISSING);
    }

    @Test void realZeroIsARealCalculationAndRecoveryClearsOldReason() {
        var result = SuggestedSalePriceResolver.evaluate(sku("1"), 0L, null, NOW);
        assertThat(result.amountCents()).isEqualTo(100L);
        assertThat(result.status()).isEqualTo(SuggestedPriceStatus.READY);
        assertThat(result.unavailableReason()).isNull();
    }

    @Test void negativeResultFromChangedSupplyBecomesFailureWithoutThrowing() {
        var sku = sku("1");
        sku.setSuggestedSalePriceRuleJson(new SuggestedSalePriceRule(SuggestedSalePriceRule.Mode.CUSTOM,
                SuggestedSalePriceRule.Base.SUPPLY_PRICE, new BigDecimal("100"),
                SuggestedSalePriceRule.Operator.SUBTRACT, new BigDecimal("90"), null, null));
        var failed = SuggestedSalePriceResolver.evaluate(sku, 8000L, null, NOW);
        assertThat(failed.amountCents()).isNull();
        assertThat(failed.status()).isEqualTo(SuggestedPriceStatus.CALCULATION_FAILED);
        assertThat(SuggestedSalePriceResolver.evaluate(sku, 10000L, null, NOW).amountCents()).isEqualTo(1000L);
    }

    @Test void legacyAmountIsNotReexecutedWhenSupplyChanges() {
        var sku = sku("1");
        sku.setSuggestedSalePriceRuleJson(SuggestedSalePriceRule.fixed(99999L));
        assertThat(SuggestedSalePriceResolver.evaluate(sku, 8000L, null, NOW).amountCents()).isEqualTo(99999L);
    }

    @Test void digestIgnoresServerSnapshotAndDecimalPresentation() {
        var first = sku("1").getSuggestedSalePriceRuleJson();
        var second = new SuggestedSalePriceRule(first.mode(), first.base(), new BigDecimal("110.00"),
                first.operator(), new BigDecimal("1.00"), null, null);
        assertThat(SuggestedSalePriceResolver.ruleDigest(first))
                .isEqualTo(SuggestedSalePriceResolver.ruleDigest(second));
    }
}
