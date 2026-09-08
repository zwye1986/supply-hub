package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 标准履约 PersistentTimer 任务类型。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public final class FulfillmentTimerTaskTypes {

    private FulfillmentTimerTaskTypes() {
    }

    public static final String OPERATION_EXECUTE = "FULFILLMENT_OPERATION_EXECUTE";
}
