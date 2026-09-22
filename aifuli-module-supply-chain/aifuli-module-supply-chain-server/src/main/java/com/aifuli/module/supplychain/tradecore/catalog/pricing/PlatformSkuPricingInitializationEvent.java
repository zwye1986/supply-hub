package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import java.util.List;

/**
 * SPU+SKU 复合保存后的平台默认定价初始化事件。事件由 Spring 同步分发，监听器与商品主数据共享供应链事务；
 * 任一税务校验、定价计算或版本发布失败都会使复合保存整体回滚。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
 */
public record PlatformSkuPricingInitializationEvent(List<String> platformSkuCodes, Long operatorId) {

    public PlatformSkuPricingInitializationEvent {
        if (platformSkuCodes == null || platformSkuCodes.isEmpty()) {
            throw new IllegalArgumentException("platform SKU codes are required");
        }
        platformSkuCodes = List.copyOf(platformSkuCodes);
    }
}
