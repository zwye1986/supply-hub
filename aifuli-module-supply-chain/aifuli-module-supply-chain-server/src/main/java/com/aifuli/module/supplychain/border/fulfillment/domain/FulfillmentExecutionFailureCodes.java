package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 执行器安全故障类别，禁止把第三方任意异常文本作为业务观察或日志。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#9-告警与观测。
 */
public final class FulfillmentExecutionFailureCodes {
    public static final String TARGET_ACCOUNT_MISSING = "TARGET_ACCOUNT_MISSING";
    public static final String TARGET_ACCOUNT_UNAVAILABLE = "TARGET_ACCOUNT_UNAVAILABLE";
    public static final String TARGET_DECRYPT_FAILED = "TARGET_DECRYPT_FAILED";
    public static final String CREDENTIAL_UNAVAILABLE = "CREDENTIAL_UNAVAILABLE";
    public static final String TICKET_PREPARATION_FAILED = "TICKET_PREPARATION_FAILED";
    public static final String ADAPTER_EXECUTION_FAILED = "ADAPTER_EXECUTION_FAILED";
    public static final String FREQUENCY_MIGRATION_MISSING = "RECHARGE_FREQUENCY_MIGRATION_MISSING";
    private FulfillmentExecutionFailureCodes() { }
}
