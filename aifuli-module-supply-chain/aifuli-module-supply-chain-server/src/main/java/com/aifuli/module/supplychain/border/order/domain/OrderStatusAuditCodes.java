package com.aifuli.module.supplychain.border.order.domain;

/**
 * 订单状态审计源事件与原因码稳定常量。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public final class OrderStatusAuditCodes {

    private OrderStatusAuditCodes() {
    }

    public static final String SOURCE_FULFILLMENT_ACCEPTED = "FULFILLMENT_ACCEPTED";
    public static final String REASON_ACCEPTED = "ACCEPTED";
    /** O2O 受理建单。 */
    public static final String SOURCE_O2O_ACCEPTED = "O2O_ACCEPTED";
    /** O2O 支付确认。 */
    public static final String SOURCE_O2O_PAID = "O2O_PAID";
    /** O2O 退款受理。 */
    public static final String SOURCE_O2O_REFUND_ACCEPTED = "O2O_REFUND_ACCEPTED";
    /** O2O 退款结果。 */
    public static final String SOURCE_O2O_REFUND_RESULT = "O2O_REFUND_RESULT";
    /** 到店购受理建单。 */
    public static final String SOURCE_INSTORE_ACCEPTED = "INSTORE_ACCEPTED";
    /** 到店购支付确认。 */
    public static final String SOURCE_INSTORE_PAID = "INSTORE_PAID";
    /** 到店购取消支付流水。 */
    public static final String SOURCE_INSTORE_CANCELLED = "INSTORE_CANCELLED";
    /** 到店购退款受理。 */
    public static final String SOURCE_INSTORE_REFUND_ACCEPTED = "INSTORE_REFUND_ACCEPTED";
    /** 到店购退款结果。 */
    public static final String SOURCE_INSTORE_REFUND_RESULT = "INSTORE_REFUND_RESULT";
    public static final String REASON_PAID = "PAID";
    public static final String REASON_REFUND = "REFUND";
}
