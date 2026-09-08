package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * BOrder / FulfillmentRequest / Operation 初始 SMALLINT 状态（一期受理落库值）。
 *
 * <p>完整状态机推进在后续适配器任务中扩展；此处仅固化受理路径使用的起始码，避免魔法数字。</p>
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public final class FulfillmentInitialStatuses {

    private FulfillmentInitialStatuses() {
    }

    /** BOrder 履约状态：待执行。 */
    public static final short B_ORDER_PENDING = 0;
    /** BOrder 结算状态：未结算。 */
    public static final short B_ORDER_SETTLEMENT_PENDING = 0;
    /** FulfillmentRequest 状态：初始。 */
    public static final short REQUEST_PENDING = 0;
    /** FulfillmentOperation 状态：待执行。 */
    public static final short OPERATION_PENDING = 0;
}
