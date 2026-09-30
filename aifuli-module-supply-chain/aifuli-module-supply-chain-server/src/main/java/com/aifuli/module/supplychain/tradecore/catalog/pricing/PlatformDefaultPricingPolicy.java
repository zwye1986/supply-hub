package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FixedOperator;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;

import java.math.BigDecimal;

/**
 * 平台 SKU 首次创建时的系统默认定价策略。它只负责提供初始化模板，不参与人工定价覆盖。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
 */
public final class PlatformDefaultPricingPolicy {

    private static final BigDecimal FULL_PERCENTAGE = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private PlatformDefaultPricingPolicy() {
    }

    public static PricingConfig create() {
        FormulaRule directMarketPrice = new FormulaRule(PriceMode.DIRECT_MARKET_PRICE,
                null, null, null, null);
        return new PricingConfig(directMarketPrice, directMarketPrice,
                new ServiceFeeRule(FULL_PERCENTAGE, FixedOperator.MULTIPLY, ZERO, true, null),
                new HideRule(ZERO, ZERO),
                new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, null));
    }
}
