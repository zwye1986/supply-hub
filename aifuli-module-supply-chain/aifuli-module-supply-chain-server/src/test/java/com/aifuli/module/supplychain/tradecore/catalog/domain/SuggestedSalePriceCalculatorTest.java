package com.aifuli.module.supplychain.tradecore.catalog.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.Base.MARKET_PRICE;
import static com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.Base.SUGGESTED_SALE_PRICE;
import static com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.Mode.CUSTOM;
import static com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.Operator.ADD;
import static com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.Operator.DIVIDE;
import static com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.Operator.MULTIPLY;
import static com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.Operator.SUBTRACT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SKU销售建议价公式纯逻辑门禁。
 * 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#221-销售建议价规则039-changeset。
 */
class SuggestedSalePriceCalculatorTest {

    @Test
    void supportsEqualMarketAndFixedModesIncludingZero() {
        assertThat(SuggestedSalePriceCalculator.calculate(
                SuggestedSalePriceRule.equalMarketPrice(), 12_345L, null).suggestedSalePriceCents())
                .isEqualTo(12_345L);
        assertThat(SuggestedSalePriceCalculator.calculate(
                SuggestedSalePriceRule.fixed(0L), 12_345L, null).suggestedSalePriceCents())
                .isZero();
    }

    @Test
    void supportsFourOperatorsAndHalfUpRounding() {
        assertThat(calculate(MARKET_PRICE, "50", ADD, "1.23", 10_001L, null))
                .isEqualTo(5_124L);
        assertThat(calculate(MARKET_PRICE, "50", SUBTRACT, "1.23", 10_001L, null))
                .isEqualTo(4_878L);
        assertThat(calculate(MARKET_PRICE, "33.33", MULTIPLY, "1.1111", 10_000L, null))
                .isEqualTo(3_703L);
        assertThat(calculate(MARKET_PRICE, "100", DIVIDE, "3", 10_000L, null))
                .isEqualTo(3_333L);
    }

    @Test
    void freezesPreviousSuggestedSalePriceAsServerSnapshot() {
        SuggestedSalePriceRule request = custom(SUGGESTED_SALE_PRICE, "110", ADD, "0");

        SuggestedSalePriceCalculator.Calculation result = SuggestedSalePriceCalculator.calculate(
                request, 20_000L, 9_900L);

        assertThat(result.suggestedSalePriceCents()).isEqualTo(10_890L);
        assertThat(result.normalizedRule().basePriceSnapshotCents()).isEqualTo(9_900L);
    }

    @Test
    void rejectsCreateSelfReferenceInvalidPrecisionDivideZeroAndNegativeResult() {
        assertThatThrownBy(() -> SuggestedSalePriceCalculator.calculate(
                custom(SUGGESTED_SALE_PRICE, "100", ADD, "0"), 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SuggestedSalePriceCalculator.calculate(
                custom(MARKET_PRICE, "10.001", ADD, "0"), 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SuggestedSalePriceCalculator.calculate(
                custom(MARKET_PRICE, "100", MULTIPLY, "1.00001"), 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SuggestedSalePriceCalculator.calculate(
                custom(MARKET_PRICE, "100", DIVIDE, "0"), 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SuggestedSalePriceCalculator.calculate(
                custom(MARKET_PRICE, "10", SUBTRACT, "11"), 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static long calculate(SuggestedSalePriceRule.Base base, String percentage,
                                  SuggestedSalePriceRule.Operator operator, String operand,
                                  long marketPrice, Long previousSuggestedSalePrice) {
        return SuggestedSalePriceCalculator.calculate(
                custom(base, percentage, operator, operand), marketPrice, previousSuggestedSalePrice)
                .suggestedSalePriceCents();
    }

    private static SuggestedSalePriceRule custom(SuggestedSalePriceRule.Base base, String percentage,
                                                 SuggestedSalePriceRule.Operator operator, String operand) {
        return new SuggestedSalePriceRule(CUSTOM, base, new BigDecimal(percentage), operator,
                new BigDecimal(operand), null, null);
    }
}
