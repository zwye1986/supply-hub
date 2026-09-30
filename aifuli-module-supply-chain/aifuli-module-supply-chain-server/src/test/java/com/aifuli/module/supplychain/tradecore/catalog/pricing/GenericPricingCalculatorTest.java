package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericPricingCalculatorTest {

    private final GenericPricingCalculator calculator = new GenericPricingCalculator();

    @Test
    void calculatesFromLeftToRightAndRoundsOnlyFinalCent() {
        PricingConfig invalidConfig = config(
                formula(PriceBase.MARKET_PRICE, "33.33", FixedOperator.MULTIPLY, "3.0000"),
                formula(PriceBase.SUGGESTED_SALE_PRICE, "100.00", FixedOperator.ADD, "0.005"));

        assertThatThrownBy(() -> calculator.calculate(invalidConfig, new PriceDependencies(1000, 1000, 800L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimal places");

        PricingConfig config = config(
                formula(PriceBase.MARKET_PRICE, "33.33", FixedOperator.MULTIPLY, "3.0000"),
                formula(PriceBase.SUGGESTED_SALE_PRICE, "100.00", FixedOperator.ADD, "0.01"));
        PricingResult result = calculator.calculate(config, new PriceDependencies(1000, 1000, 800L));
        assertThat(result.distributionPriceCents()).isEqualTo(1000L);
        assertThat(result.salesPriceCents()).isEqualTo(1001L);
    }

    @Test
    void allowsZeroResultButRejectsNegativeAndDivisionByZero() {
        PricingConfig zero = config(
                formula(PriceBase.MARKET_PRICE, "100", FixedOperator.SUBTRACT, "10"),
                formula(PriceBase.MARKET_PRICE, "100", FixedOperator.SUBTRACT, "10"));
        PricingResult result = calculator.calculate(zero, new PriceDependencies(1000, 0, 800L));
        assertThat(result.distributionPriceCents()).isZero();
        assertThat(result.salesPriceCents()).isZero();

        PricingConfig negative = config(
                formula(PriceBase.MARKET_PRICE, "100", FixedOperator.SUBTRACT, "10.01"), direct());
        assertThatThrownBy(() -> calculator.calculate(negative, new PriceDependencies(1000, 0, 800L)))
                .hasMessageContaining("must not be negative");

        PricingConfig divideZero = config(
                formula(PriceBase.MARKET_PRICE, "100", FixedOperator.DIVIDE, "0"), direct());
        assertThatThrownBy(() -> calculator.calculate(divideZero, new PriceDependencies(1000, 0, 800L)))
                .hasMessageContaining("must not be zero");
    }

    @Test
    void usesStrictMarginThresholdAndHigherOfTwoInputs() {
        PricingConfig config = new PricingConfig(
                formula(PriceBase.SUPPLY_PRICE, "110", FixedOperator.ADD, "0"), direct(),
                fee(), new HideRule(new BigDecimal("9"), new BigDecimal("10")), tax());
        PricingResult equal = calculator.calculate(config, new PriceDependencies(2000, 2000, 1000L));
        assertThat(equal.distributionMarginPercent()).isEqualByComparingTo("10.0000");
        assertThat(equal.effectiveHideThresholdPercent()).isEqualByComparingTo("10");
        assertThat(equal.status()).isEqualTo(PricingStatus.READY);

        PricingConfig hidden = new PricingConfig(config.distributionRule(), config.salesRule(), fee(),
                new HideRule(new BigDecimal("10.01"), null), tax());
        assertThat(calculator.calculate(hidden, new PriceDependencies(2000, 2000, 1000L)).status())
                .isEqualTo(PricingStatus.MARGIN_HIDDEN);
    }

    @Test
    void hidesWhenUnroundedMarginIsStrictlyBelowThresholdEvenIfFourDecimalSnapshotEquals() {
        PricingConfig config = new PricingConfig(direct(), direct(), fee(),
                new HideRule(new BigDecimal("10"), null), tax());
        PricingResult result = calculator.calculate(config, new PriceDependencies(2_750_010L, 2_750_010L, 2_500_010L));
        assertThat(result.distributionPriceCents()).isEqualTo(2_750_010L);
        assertThat(result.distributionMarginPercent()).isEqualByComparingTo("10.0000");
        assertThat(result.status()).isEqualTo(PricingStatus.MARGIN_HIDDEN);
        assertThat(result.unavailableReason()).isEqualTo(PricingUnavailableReason.DISTRIBUTION_MARGIN_BELOW_THRESHOLD.name());
    }

    @Test
    void missingOrZeroSupplyMakesSkuUnavailableAndServiceFeeUsesSubtotal() {
        PricingResult missing = calculator.calculate(config(direct(), direct()),
                new PriceDependencies(0, 0, null));
        assertThat(missing.distributionPriceCents()).isZero();
        assertThat(missing.status()).isEqualTo(PricingStatus.DEPENDENCY_MISSING);

        PricingConfig supplySubtract = config(
                formula(PriceBase.SUPPLY_PRICE, "100", FixedOperator.SUBTRACT, "10.01"), direct());
        PricingResult zeroSupply = calculator.calculate(supplySubtract, new PriceDependencies(1000, 1000, 0L));
        assertThat(zeroSupply.status()).isEqualTo(PricingStatus.DEPENDENCY_MISSING);
        assertThat(zeroSupply.unavailableReason()).isEqualTo(PricingUnavailableReason.EFFECTIVE_SUPPLY_PRICE_ZERO.name());
        assertThat(zeroSupply.distributionPriceCents()).isNull();

        long fee = calculator.calculateServiceFee(
                new ServiceFeeRule(new BigDecimal("2.50"), FixedOperator.ADD,
                        new BigDecimal("1.00"), true, null), 10_000);
        assertThat(fee).isEqualTo(350L);
    }

    @Test
    void omittedTaxIncludedDoesNotDefaultToExcluded() {
        PricingConfig config = new PricingConfig(direct(), direct(), fee(), new HideRule(null, null),
                new TaxMetadata(null, PricingTaxRateCodes.NON_TAXABLE, "不征税"));
        assertThatThrownBy(() -> calculator.calculate(config, new PriceDependencies(1000, 1000, 800L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
    }

    private static PricingConfig config(FormulaRule distribution, FormulaRule sales) {
        return new PricingConfig(distribution, sales, fee(), new HideRule(null, null), tax());
    }

    private static FormulaRule formula(PriceBase base, String percent, FixedOperator operator, String operand) {
        return new FormulaRule(PriceMode.CUSTOM, base, new BigDecimal(percent), operator, new BigDecimal(operand));
    }

    private static FormulaRule direct() {
        return new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null);
    }

    private static ServiceFeeRule fee() {
        return new ServiceFeeRule(new BigDecimal("1"), FixedOperator.MULTIPLY,
                BigDecimal.ZERO, true, null);
    }

    private static TaxMetadata tax() {
        return new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税");
    }
}
