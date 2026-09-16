package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FixedOperator;
import static com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import static com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import static com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceBase;
import static com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceMode;
import static com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import static com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import static com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目五组配置继承合并的纯逻辑门禁。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
 */
class ProjectPricingConfigMergerTest {

    @Test
    void inheritsAllGroupsFromBuyerWhenProjectDoesNotOverride() {
        PricingConfig parent = parent();
        PricingConfig merged = ProjectPricingConfigMerger.merge(parent, ProjectOverrides.allInherited());
        assertThat(merged).isEqualTo(parent);
    }

    @Test
    void overridesSalesOnlyAndKeepsOtherBuyerGroups() {
        FormulaRule projectSales = new FormulaRule(PriceMode.CUSTOM, PriceBase.MARKET_PRICE,
                new BigDecimal("120"), FixedOperator.ADD, BigDecimal.ZERO);
        PricingConfig merged = ProjectPricingConfigMerger.merge(parent(),
                new ProjectOverrides(true, null, false, projectSales, true, null, true, null, true, null));
        assertThat(merged.distributionRule()).isEqualTo(parent().distributionRule());
        assertThat(merged.salesRule()).isEqualTo(projectSales);
        assertThat(merged.serviceFeeRule()).isEqualTo(parent().serviceFeeRule());
        assertThat(merged.hideRule()).isEqualTo(parent().hideRule());
        assertThat(merged.taxMetadata()).isEqualTo(parent().taxMetadata());
    }

    @Test
    void rejectsMissingOverrideWhenInheritIsFalse() {
        assertThatThrownBy(() -> ProjectPricingConfigMerger.merge(parent(),
                new ProjectOverrides(false, null, true, null, true, null, true, null, true, null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("project override config is required");
    }

    private static PricingConfig parent() {
        FormulaRule direct = new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null);
        return new PricingConfig(direct, direct,
                new ServiceFeeRule(new BigDecimal("2"), FixedOperator.ADD, new BigDecimal("1.00"), false, "服务费："),
                new HideRule(new BigDecimal("10"), new BigDecimal("5")),
                new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税"));
    }
}
