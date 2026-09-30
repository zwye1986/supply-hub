package com.aifuli.module.supplychain.border.o2o.domain;

/**
 * O2O 受理与退款产生的 Outbox 聚合类型、事件类型与投递目标稳定编码。
 *
 * <p>O2O 通知通过 Outbox 投递到渠道服务 CHANNEL_NOTIFY 端点，再由渠道服务调用 ele.me 回调。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
public final class O2OOutboxCodes {

    private O2OOutboxCodes() {
    }

    public static final String AGGREGATE_O2O_DETAIL = "O2O_DETAIL";
    public static final String AGGREGATE_O2O_REFUND_CASE = "O2O_REFUND_CASE";

    /** 支付确认后通知渠道（ele.me payCallback）。 */
    public static final String EVENT_O2O_PAYMENT_NOTIFY = "O2O_PAYMENT_NOTIFY";
    /** 退款结果通知渠道（ele.me refundCallback）。 */
    public static final String EVENT_O2O_REFUND_NOTIFY = "O2O_REFUND_NOTIFY";
    /** O2O 订单摘要变更，投递给采购商商城。 */
    public static final String EVENT_O2O_ORDER_SUMMARY_CHANGED = "O2O_ORDER_SUMMARY_CHANGED";

    /** 渠道服务 CHANNEL_NOTIFY 端点目标。 */
    public static final String TARGET_TAOGAO_FLASH_CHANNEL = "taobao-flash-channel-notify";
    /** 采购商事件 RPC 目标（复用标准履约同一目标命名空间）。 */
    public static final String TARGET_BUYER_EVENT_RPC = "buyer-event-rpc";
}
