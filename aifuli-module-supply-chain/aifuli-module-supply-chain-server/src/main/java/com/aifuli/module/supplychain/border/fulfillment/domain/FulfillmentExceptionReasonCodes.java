package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 履约异常 Case 与订单状态日志稳定原因码。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#74-fulfillmentexceptioncase-与奇那梦卡密恢复}。</p>
 */
public final class FulfillmentExceptionReasonCodes {

    private FulfillmentExceptionReasonCodes() {
    }

    /** 写入 {@code sc_fulfillment_exception_case.reason_code}。 */
    public static final String QNM_ASSET_MISSING_AFTER_SUCCESS = "QNM_ASSET_MISSING_AFTER_SUCCESS";

    /** 写入 {@code sc_order_status_log.reason_code}，与 §10.1 人工处置码对齐。 */
    public static final String QNM_ASSET_RECOVERY_001 = "QNM-ASSET-RECOVERY-001";
}
