package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;

import java.util.Objects;

/**
 * 下游五组配置相对直接上游有效值的继承合并。分销商以商品默认层为上游，项目以分销商层为上游。
 * 该纯函数由预览、保存、依赖重算、报价与单测共用，保证三层解析语义一致。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
 */
public final class ProjectPricingConfigMerger {

    private ProjectPricingConfigMerger() {
    }

    public static PricingConfig merge(PricingConfig parent, ProjectOverrides override) {
        Objects.requireNonNull(parent, "parent pricing config is required");
        Objects.requireNonNull(override, "downstream overrides are required");
        return new PricingConfig(
                override.distributionInherited() ? parent.distributionRule() : require(override.distributionRule()),
                override.salesInherited() ? parent.salesRule() : require(override.salesRule()),
                override.serviceFeeInherited() ? parent.serviceFeeRule() : require(override.serviceFeeRule()),
                override.hideInherited() ? parent.hideRule() : require(override.hideRule()),
                override.taxInherited() ? parent.taxMetadata() : require(override.taxMetadata()));
    }

    private static <T> T require(T value) {
        return Objects.requireNonNull(value, "downstream override config is required when inherit=false");
    }
}
