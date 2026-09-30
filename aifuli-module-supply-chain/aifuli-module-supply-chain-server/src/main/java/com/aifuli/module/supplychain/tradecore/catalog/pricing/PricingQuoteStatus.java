package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/**
 * 标准商品报价凭证状态。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#7-项目报价凭证与标准履约}。
 */
public enum PricingQuoteStatus {
    ISSUED,
    CONSUMED
}
