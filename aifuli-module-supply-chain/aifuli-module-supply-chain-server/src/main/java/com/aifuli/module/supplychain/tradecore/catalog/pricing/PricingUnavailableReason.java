package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/**
 * 用户端不可售原因编码，持久化到定价身份与版本的 unavailable_reason。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
 */
public enum PricingUnavailableReason {
    EFFECTIVE_SUPPLY_PRICE_MISSING,
    EFFECTIVE_SUPPLY_PRICE_ZERO,
    FORMULA_BASE_PRICE_MISSING,
    DISTRIBUTION_MARGIN_BELOW_THRESHOLD,
    PRICE_DEPENDENCY_CHANGED
}
