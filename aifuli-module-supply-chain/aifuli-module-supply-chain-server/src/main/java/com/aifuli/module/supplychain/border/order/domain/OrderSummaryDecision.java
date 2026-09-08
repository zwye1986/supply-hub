package com.aifuli.module.supplychain.border.order.domain;

/** OrderSummaryPolicy 计算出的公共订单状态；唯一写入总表 orderStatus/aftersaleStatus/businessStage。 */
public record OrderSummaryDecision(short orderStatus, short aftersaleStatus, String businessStage) {
}
