package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 履约异常 Case 两步命令与执行状态的持久协议码，与命令表 CHECK 及管理端响应一致。
 * 不复用订单／供应商成功状态：命令 SUCCEEDED 仅表示核实或处置执行完成，不代表履约成功。
 * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#7-持久化与协议兼容。
 */
public final class FulfillmentCaseActionCodes {
    public static final String VERIFY_ORIGIN = "VERIFY_ORIGIN";
    public static final String CONFIRM_FAILURE = "CONFIRM_FAILURE";
    public static final String RECEIVED = "RECEIVED";
    public static final String STARTED = "STARTED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";

    private FulfillmentCaseActionCodes() {
    }
}
