package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/**
 * 仅表达已识别的SKU/SPU、分销商品或项目选品不可用；JSON、数据库与引用矛盾不得使用该异常。
 * 保持IllegalStateException基类兼容内部RPC；HTTP仅转换该专用类型，保留因果。
 * 设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#3-定价身份与当前准入。
 */
public final class ProductNotSaleableException extends IllegalStateException {
    public ProductNotSaleableException(String message) { super(message); }
}
