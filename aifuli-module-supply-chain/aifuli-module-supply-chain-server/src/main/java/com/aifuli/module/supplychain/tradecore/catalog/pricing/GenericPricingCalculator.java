package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 通用定价纯计算器。所有中间值使用十进制定点数，仅最终金额按 HALF_UP 舍入到 CNY 分。
 * 该类不读取数据库，批量预览、保存、依赖重算和报价必须共用它，避免四套公式漂移。
 * 利润隐藏判定使用未舍入交叉相乘，四位小数只作为展示快照。
 * 以 {@link PriceBase#SUPPLY_PRICE} 为基数且供货价缺失或为 0 时直接返回空价，
 * 由 {@link #calculate} 落成 {@link PricingStatus#DEPENDENCY_MISSING}，不得先算出负数再整批失败。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#2-公式与精度}。
 */
public final class GenericPricingCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public PricingResult calculate(PricingConfig config, PriceDependencies dependencies) {
        Objects.requireNonNull(config, "pricing config is required");
        Objects.requireNonNull(dependencies, "price dependencies are required");
        validate(config);

        Long distribution = calculatePrice(config.distributionRule(), dependencies);
        Long sales = calculatePrice(config.salesRule(), dependencies);
        BigDecimal threshold = effectiveThreshold(config.hideRule());
        if (dependencies.supplyPriceCents() == null || dependencies.supplyPriceCents() == 0L) {
            return new PricingResult(distribution, sales, null, threshold,
                    PricingStatus.DEPENDENCY_MISSING, dependencies.supplyPriceCents() == null
                    ? PricingUnavailableReason.EFFECTIVE_SUPPLY_PRICE_MISSING.name()
                    : PricingUnavailableReason.EFFECTIVE_SUPPLY_PRICE_ZERO.name());
        }
        if (distribution == null || sales == null) {
            return new PricingResult(distribution, sales, null, threshold,
                    PricingStatus.DEPENDENCY_MISSING, PricingUnavailableReason.FORMULA_BASE_PRICE_MISSING.name());
        }
        long supplyCents = dependencies.supplyPriceCents();
        BigDecimal profitTimesHundred = BigDecimal.valueOf(distribution - supplyCents).multiply(ONE_HUNDRED);
        BigDecimal supply = BigDecimal.valueOf(supplyCents);
        BigDecimal snapshot = profitTimesHundred.divide(supply, 4, RoundingMode.HALF_UP);
        boolean hidden = threshold != null && profitTimesHundred.compareTo(threshold.multiply(supply)) < 0;
        return new PricingResult(distribution, sales, snapshot, threshold,
                hidden ? PricingStatus.MARGIN_HIDDEN : PricingStatus.READY,
                hidden ? PricingUnavailableReason.DISTRIBUTION_MARGIN_BELOW_THRESHOLD.name() : null);
    }

    public long calculateServiceFee(ServiceFeeRule rule, long productSubtotalCents) {
        Objects.requireNonNull(rule, "service fee rule is required");
        if (productSubtotalCents < 0) {
            throw new IllegalArgumentException("product subtotal must not be negative");
        }
        validatePercentage(rule.percentage());
        validateOperand(rule.operator(), rule.operand());
        BigDecimal subtotalYuan = BigDecimal.valueOf(productSubtotalCents, 2);
        BigDecimal percentageResult = subtotalYuan.multiply(rule.percentage()).divide(ONE_HUNDRED);
        return toCents(applyOperator(percentageResult, rule.operator(), rule.operand()));
    }

    private static Long calculatePrice(FormulaRule rule, PriceDependencies dependencies) {
        if (rule.mode() == PriceMode.DIRECT_MARKET_PRICE) {
            return dependencies.marketPriceCents();
        }
        Long baseCents = switch (rule.base()) {
            case MARKET_PRICE -> dependencies.marketPriceCents();
            case SUGGESTED_SALE_PRICE -> dependencies.suggestedSalePriceCents();
            case SUPPLY_PRICE -> dependencies.supplyPriceCents();
        };
        if (baseCents == null || (rule.base() == PriceBase.SUPPLY_PRICE && baseCents == 0L)) {
            return null;
        }
        BigDecimal baseYuan = BigDecimal.valueOf(baseCents, 2);
        BigDecimal percentageResult = baseYuan.multiply(rule.percentage()).divide(ONE_HUNDRED);
        return toCents(applyOperator(percentageResult, rule.operator(), rule.operand()));
    }

    private static BigDecimal applyOperator(BigDecimal left, FixedOperator operator, BigDecimal operand) {
        return switch (operator) {
            case ADD -> left.add(operand);
            case SUBTRACT -> left.subtract(operand);
            case MULTIPLY -> left.multiply(operand);
            case DIVIDE -> left.divide(operand, 12, RoundingMode.HALF_UP);
        };
    }

    private static long toCents(BigDecimal yuan) {
        BigDecimal cents = yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP);
        if (cents.signum() < 0) {
            throw new IllegalArgumentException("pricing result must not be negative");
        }
        return cents.longValueExact();
    }

    private static void validate(PricingConfig config) {
        validateFormula(config.distributionRule());
        validateFormula(config.salesRule());
        validatePercentage(config.serviceFeeRule().percentage());
        validateOperand(config.serviceFeeRule().operator(), config.serviceFeeRule().operand());
        if (!config.serviceFeeRule().hideCopy()
                && (config.serviceFeeRule().copy() == null || config.serviceFeeRule().copy().isBlank())) {
            throw new IllegalArgumentException("service fee copy is required when it is displayed");
        }
        validateThreshold(config.hideRule().distributionThresholdPercent());
        validateThreshold(config.hideRule().salesThresholdPercent());
        Boolean taxIncluded = config.taxMetadata().taxIncluded();
        String taxRateCode = config.taxMetadata().taxRateCode();
        if (taxIncluded == null || taxRateCode == null || taxRateCode.isBlank()) {
            throw new IllegalArgumentException("tax included flag and tax rate code must be provided together");
        }
        if (!taxIncluded && !PricingTaxRateCodes.NON_TAXABLE.equals(taxRateCode)) {
            throw new IllegalArgumentException("tax excluded pricing must use NON_TAXABLE");
        }
    }

    private static void validateFormula(FormulaRule rule) {
        Objects.requireNonNull(rule, "formula rule is required");
        if (rule.mode() == PriceMode.DIRECT_MARKET_PRICE) {
            return;
        }
        Objects.requireNonNull(rule.base(), "formula base is required");
        validatePercentage(rule.percentage());
        validateOperand(rule.operator(), rule.operand());
    }

    private static void validatePercentage(BigDecimal percentage) {
        if (percentage == null || percentage.signum() <= 0 || scale(percentage) > 2) {
            throw new IllegalArgumentException("percentage must be positive with at most 2 decimal places");
        }
    }

    private static void validateOperand(FixedOperator operator, BigDecimal operand) {
        if (operator == null || operand == null || operand.signum() < 0) {
            throw new IllegalArgumentException("operator and non-negative operand are required");
        }
        int maxScale = operator == FixedOperator.ADD || operator == FixedOperator.SUBTRACT ? 2 : 4;
        if (scale(operand) > maxScale) {
            throw new IllegalArgumentException("operand decimal places exceed operator limit");
        }
        if (operator == FixedOperator.DIVIDE && operand.signum() == 0) {
            throw new IllegalArgumentException("division operand must not be zero");
        }
    }

    private static void validateThreshold(BigDecimal threshold) {
        if (threshold != null && (threshold.signum() < 0
                || threshold.compareTo(ONE_HUNDRED) > 0 || scale(threshold) > 2)) {
            throw new IllegalArgumentException("hide threshold must be 0 to 100 with at most 2 decimal places");
        }
    }

    private static int scale(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    private static BigDecimal effectiveThreshold(HideRule rule) {
        BigDecimal distribution = rule.distributionThresholdPercent();
        BigDecimal sales = rule.salesThresholdPercent();
        if (distribution == null) {
            return sales;
        }
        if (sales == null) {
            return distribution;
        }
        return distribution.max(sales);
    }

    public enum PriceMode { DIRECT_MARKET_PRICE, CUSTOM }
    public enum PriceBase { MARKET_PRICE, SUGGESTED_SALE_PRICE, SUPPLY_PRICE }
    public enum FixedOperator { ADD, SUBTRACT, MULTIPLY, DIVIDE }
    public enum PricingStatus {
        UNPRICED, READY, RECALCULATING, DEPENDENCY_MISSING, CALCULATION_FAILED, MARGIN_HIDDEN
    }

    public record FormulaRule(PriceMode mode, PriceBase base, BigDecimal percentage,
                              FixedOperator operator, BigDecimal operand) { }

    public record ServiceFeeRule(BigDecimal percentage, FixedOperator operator, BigDecimal operand,
                                 boolean hideCopy, String copy) { }

    public record HideRule(BigDecimal distributionThresholdPercent,
                           BigDecimal salesThresholdPercent) { }

    public record TaxMetadata(Boolean taxIncluded, String taxRateCode, String taxRateLabel) { }

    public record PricingConfig(FormulaRule distributionRule, FormulaRule salesRule,
                                ServiceFeeRule serviceFeeRule, HideRule hideRule,
                                TaxMetadata taxMetadata) { }

    public record PriceDependencies(long marketPriceCents, long suggestedSalePriceCents,
                                    Long supplyPriceCents) { }

    public record PricingResult(Long distributionPriceCents, Long salesPriceCents,
                                BigDecimal distributionMarginPercent,
                                BigDecimal effectiveHideThresholdPercent,
                                PricingStatus status, String unavailableReason) { }
}
