package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/**
 * 旧内部受理消费报价的过渡上下文：按项目永久键锁定唯一报价，并额外核对过渡 token 指向该报价。
 * 不再携带用户或支付时间语义。
 * 设计见 docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#3-受理内查取与唯一消费。
 */
public record QuoteConsumptionContext(String token, Long buyerId, Long projectId, Long platformSkuId,
                                      String buyerOrderNo, long quantity) {
}
