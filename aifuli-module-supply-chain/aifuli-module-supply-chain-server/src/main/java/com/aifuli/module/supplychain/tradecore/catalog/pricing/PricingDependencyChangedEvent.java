package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/**
 * 商品基础价或有效供货条件变化事件。Spring 默认同步派发，定价监听器必须在发布方原事务内完成版本切换。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
 */
public record PricingDependencyChangedEvent(Long platformSkuId) {
    public PricingDependencyChangedEvent {
        if (platformSkuId == null) {
            throw new IllegalArgumentException("platformSkuId is required");
        }
    }
}
