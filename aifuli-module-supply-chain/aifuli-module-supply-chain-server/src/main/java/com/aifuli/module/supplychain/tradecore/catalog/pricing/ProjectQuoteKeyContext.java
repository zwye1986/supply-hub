package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/**
 * 首次价格冻结与共享受理消费报价的可信项目键上下文：仅凭「采购商+项目+平台SKU+项目订单号+数量」锁定首次冻结报价，
 * 不携带用户、支付时间、报价 token 或调用方商品版本。
 * 设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#3-定价身份与当前准入。
 */
public record ProjectQuoteKeyContext(Long buyerId, Long projectId, Long platformSkuId,
                                      String buyerOrderNo, long quantity) {
}
