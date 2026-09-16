package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/**
 * 税率字典中的特殊编码。动态税率不能做成封闭枚举，但「不征税」必须全链路共用同一常量。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性}。
 */
public final class PricingTaxRateCodes {
    public static final String NON_TAXABLE = "NON_TAXABLE";

    private PricingTaxRateCodes() {
    }
}
