package com.aifuli.module.supplychain.border.instore.domain;

/**
 * 到店购受理与退款产生的 Outbox 聚合类型、事件类型与投递目标稳定编码。
 *
 * <p>与 O2O 区别：到店购为 pull 模型，无渠道通知事件（无 O2O_PAYMENT_NOTIFY/O2O_REFUND_NOTIFY 对应物），
 * 旅通主动调查支付与退款状态；Outbox 只投递采购商订单摘要变更事件。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#8-与-eleme-的边界与共享}。</p>
 */
public final class InstoreOutboxCodes {

    private InstoreOutboxCodes() {
    }

    public static final String AGGREGATE_INSTORE_ORDER = "INSTORE_ORDER";
    public static final String AGGREGATE_INSTORE_REFUND_CASE = "INSTORE_REFUND_CASE";

    /** 到店购订单摘要变更，投递给采购商商城。 */
    public static final String EVENT_INSTORE_ORDER_SUMMARY_CHANGED = "INSTORE_ORDER_SUMMARY_CHANGED";

    /** 采购商事件 RPC 目标（复用标准履约与 O2O 同一目标命名空间）。 */
    public static final String TARGET_BUYER_EVENT_RPC = "buyer-event-rpc";
}
