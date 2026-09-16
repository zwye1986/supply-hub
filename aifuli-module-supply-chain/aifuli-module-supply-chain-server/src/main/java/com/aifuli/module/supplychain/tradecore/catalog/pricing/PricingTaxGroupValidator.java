package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/**
 * 税务展示字段必须作为原子组校验：覆盖时两字段都显式出现，继承时不得携带覆盖字段。
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

    public static void requireProjectGroup(boolean taxInherited, boolean metadataPresent,
                                           Boolean taxIncluded, String taxRateCode) {
        if (taxInherited) {
            if (metadataPresent) {
                throw new IllegalArgumentException("inherited tax must not carry override fields");
            }
            return;
        }
        if (!metadataPresent) {
            throw new IllegalArgumentException("project tax override requires tax metadata");
        }
        requireComplete(taxIncluded, taxRateCode);
    }
}
