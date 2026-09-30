package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 履约幂等键前缀与构造，避免 Lifecycle 内散落业务字面量。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public final class FulfillmentIdempotencyKeys {

    private FulfillmentIdempotencyKeys() {
    }

    public static String bOrderFulfill(String supplierCode, String bOrderNo) {
        return "FULFILL:" + supplierCode + ":" + bOrderNo;
    }

    public static String operation(String bOrderNo, int sequenceNo) {
        return "OP:" + bOrderNo + ":" + sequenceNo;
    }
}
