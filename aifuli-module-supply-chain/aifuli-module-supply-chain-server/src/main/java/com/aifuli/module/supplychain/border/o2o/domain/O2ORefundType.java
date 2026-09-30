package com.aifuli.module.supplychain.border.o2o.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * O2O 退款类型，对应 {@code sc_o2o_refund_case.refund_type}。
 *
 * <p>取值与 DDL CHECK 约束一致。普通渠道退款与拒付补偿三者一期相等；
 * 迟到支付补偿只退用户，账户返还金额为零。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.3-O2ORefundCase}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum O2ORefundType {
    /** 渠道退款：channelRefundAmount = buyerActualRefundAmount = accountReturnAmount。 */
    CHANNEL_REFUND("渠道退款"),
    /** 拒付补偿：三者一期相等。 */
    REJECTION_COMPENSATION("拒付补偿"),
    /** 迟到支付补偿：只退用户，账户返还金额为零。 */
    LATE_PAYMENT_COMPENSATION("迟到支付补偿");

    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;
}
