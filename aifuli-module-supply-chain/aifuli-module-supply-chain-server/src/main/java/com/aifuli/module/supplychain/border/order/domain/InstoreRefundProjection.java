package com.aifuli.module.supplychain.border.order.domain;

/**
 * INSTORE 售后投影封闭事实：由 Lifecycle 从退款 Case 与订单头累计值组装后提交给
 * {@link InstoreOrderSummaryPolicy}。hasOpenRefund 覆盖排队中、退款中与人工 Case
 * （人工属于"退款协同进入人工处理"，投影为处理中）。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}（售后目标态）。
 */
public record InstoreRefundProjection(boolean hasOpenRefund,
                                      long totalChannelRefundAmount,
                                      long totalPaidAmount) {
}
