package com.aifuli.module.supplychain.border.order.domain;

/**
 * 场景模块提交给 OrderSummaryPolicy 的封闭事实；总表不反向读取场景详情，只据此计算公共状态。
 *
 * <p>字段保持不可变与可序列化，便于审计与事件回放。</p>
 */
public record OrderSummaryFact(String orderType, String detailType, short fulfillmentStatus,
                                long requestedQuantity, long fulfilledQuantity,
                                String rejectionCode) {
}
