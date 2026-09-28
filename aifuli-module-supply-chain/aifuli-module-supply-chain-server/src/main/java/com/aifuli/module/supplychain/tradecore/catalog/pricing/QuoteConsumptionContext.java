package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import java.time.LocalDateTime;

/**
 * 标准履约消费报价凭证时的绑定上下文，避免 8 个标量参数顺序传错。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#7-项目报价凭证与标准履约}。
 */
public record QuoteConsumptionContext(String token, Long buyerId, Long projectId, Long platformSkuId,
                                      String buyerOrderNo, String buyerUserId, long quantity,
                                      LocalDateTime paidAt) {
}
