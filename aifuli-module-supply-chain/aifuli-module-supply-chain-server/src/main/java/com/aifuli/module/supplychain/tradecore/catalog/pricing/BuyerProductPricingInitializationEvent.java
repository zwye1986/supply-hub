package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import java.util.Objects;

/**
 * 新建或复活分销商品后的通用定价同步初始化事件。
 * 监听器与目录写入处于同一事务：商品默认层已有版本时立即发布分销商继承版本，否则保留 UNPRICED。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
 */
public record BuyerProductPricingInitializationEvent(Long buyerProductId, Long operatorId) {

    public BuyerProductPricingInitializationEvent {
        Objects.requireNonNull(buyerProductId, "buyerProductId is required");
    }
}
