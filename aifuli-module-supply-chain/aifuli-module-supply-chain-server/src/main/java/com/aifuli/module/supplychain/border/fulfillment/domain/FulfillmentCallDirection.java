package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 供应商调用审计方向，对应 {@code sc_fulfillment_call_log.direction} CHECK。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
public enum FulfillmentCallDirection {
    OUTBOUND,
    INBOUND
}
