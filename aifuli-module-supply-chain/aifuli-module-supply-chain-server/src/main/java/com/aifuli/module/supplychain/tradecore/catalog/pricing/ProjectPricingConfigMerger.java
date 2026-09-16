package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;

import java.util.Objects;

/**
 * 项目五组配置相对分销商默认值的继承合并。纯函数，供预览、保存、报价与单测共用。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
 */
public final class ProjectPricingConfigMerger {

    private ProjectPricingConfigMerger() {
    }

    public static PricingConfig merge(PricingConfig parent, ProjectOverrides override) {
        Objects.requireNonNull(parent, "buyer pricing config is required");
        Objects.requireNonNull(override, "project overrides are required");
        return new PricingConfig(
                override.distributionInherited() ? parent.distributionRule() : require(override.distributionRule()),
                override.salesInherited() ? parent.salesRule() : require(override.salesRule()),
                override.serviceFeeInherited() ? parent.serviceFeeRule() : require(override.serviceFeeRule()),
                override.hideInherited() ? parent.hideRule() : require(override.hideRule()),
                override.taxInherited() ? parent.taxMetadata() : require(override.taxMetadata()));
    }

    private static <T> T require(T value) {
        return Objects.requireNonNull(value, "project override config is required when inherit=false");
    }
}
