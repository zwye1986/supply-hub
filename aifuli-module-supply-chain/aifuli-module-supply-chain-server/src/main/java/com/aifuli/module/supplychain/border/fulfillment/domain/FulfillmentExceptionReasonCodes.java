package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 履约异常 Case 与订单状态日志稳定原因码。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#74-fulfillmentexceptioncase-与奇那梦卡密恢复}。</p>
 */
public final class FulfillmentExceptionReasonCodes {

    /** 权威供应商终态相互矛盾，只审计核实，不能覆盖资金事实。 */
    public static final String SUPPLIER_TERMINAL_CONTRADICTION = "SUPPLIER_TERMINAL_CONTRADICTION";

    /** 华昇金额、身份或资产冲突，保留资金与加密证据；设计见华昇生产闭环设计 §6.3。 */
    public static final String HUASHENG_FACT_CONFLICT = "HUASHENG_FACT_CONFLICT";

    /** 华昇自动恢复到限，仅停止外呼，仍允许可信回调核实；设计同 §5.3、§8.2。 */
    public static final String HUASHENG_RECOVERY_LIMIT = "HUASHENG_RECOVERY_LIMIT";

    /** 华昇本地准备或 checkpoint 无法核实，保留原单与资金；设计同 §4.2、§5.3。 */
    public static final String HUASHENG_PREPARATION_FAILED = "HUASHENG_PREPARATION_FAILED";

    private FulfillmentExceptionReasonCodes() {
    }

    /** 写入 {@code sc_fulfillment_exception_case.reason_code}。 */
    public static final String QNM_ASSET_MISSING_AFTER_SUCCESS = "QNM_ASSET_MISSING_AFTER_SUCCESS";

    /** 写入 {@code sc_order_status_log.reason_code}，与 §10.1 人工处置码对齐。 */
    public static final String QNM_ASSET_RECOVERY_001 = "QNM-ASSET-RECOVERY-001";
}
