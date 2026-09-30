package com.aifuli.module.supplychain.border.instore.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 到店购外部操作类型，对应 {@code sc_instore_operation.operation_type}。
 *
 * <p>取值与 DDL CHECK 约束一致：PREORDER 预下单，PAYMENT_QUERY 支付查单，
 * CANCEL_PAY 取消支付流水，BUYER_REFUND 采购商退款，REFUND_QUERY 退款查单。
 * 与 O2O 操作类型区别：无 CHANNEL_NOTIFY（旅通为 pull 模型，中台不推送渠道）。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#6-渠道服务-aiful-channel-rtmart-server}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InstoreOperationType {
    /** 预下单：建单并冻结资金。 */
    PREORDER("预下单"),
    /** 支付查单：超时按原 outTradeNo 查支付结果。 */
    PAYMENT_QUERY("支付查单"),
    /** 取消支付流水：旅通接口④，已支付则转退款。 */
    CANCEL_PAY("取消支付流水"),
    /** 采购商退款：调收银台退款。 */
    BUYER_REFUND("采购商退款"),
    /** 退款查单：超时按原 refund_bn 查退款结果。 */
    REFUND_QUERY("退款查单");

    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;
}
