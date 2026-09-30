package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/**
 * 税务展示字段必须作为原子组校验：`taxIncluded` 与 `taxRateCode` 缺一不可。
 * 下游两层（分销商/项目）接口的继承/覆盖边界由管理端映射层
 * {@code GenericPricingRequestMapper.normalizeTaxGroup} 完成降级归一化，本类只保留平台默认层的完整性校验。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#1-范围与不变量}。
 */
public final class PricingTaxGroupValidator {

    private PricingTaxGroupValidator() {
    }

    public static void requireComplete(Boolean taxIncluded, String taxRateCode) {
        if (taxIncluded == null || taxRateCode == null || taxRateCode.isBlank()) {
            throw new IllegalArgumentException("tax included flag and tax rate code must be provided together");
        }
    }
}
