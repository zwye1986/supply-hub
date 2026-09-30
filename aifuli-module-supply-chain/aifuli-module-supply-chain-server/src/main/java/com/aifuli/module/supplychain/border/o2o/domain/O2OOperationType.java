package com.aifuli.module.supplychain.border.o2o.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * O2O 外部操作类型，对应 {@code sc_o2o_operation.operation_type}。
 *
 * <p>取值与 DDL CHECK 约束一致：PREORDER 预下单，PAYMENT_QUERY 支付查单，
 * BUYER_REFUND 采购商退款，REFUND_QUERY 退款查单，CHANNEL_NOTIFY 渠道通知。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum O2OOperationType {
    /** 预下单：抢占后调收银台。 */
    PREORDER("预下单"),
    /** 支付查单：超时按原 outTradeName 查支付结果。 */
    PAYMENT_QUERY("支付查单"),
    /** 采购商退款：调收银台退款。 */
    BUYER_REFUND("采购商退款"),
    /** 退款查单：超时按原 refundKey 查退款结果。 */
    REFUND_QUERY("退款查单"),
    /** 渠道通知：向 ele.me 推送支付/退款回调。 */
    CHANNEL_NOTIFY("渠道通知");

    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;
}
