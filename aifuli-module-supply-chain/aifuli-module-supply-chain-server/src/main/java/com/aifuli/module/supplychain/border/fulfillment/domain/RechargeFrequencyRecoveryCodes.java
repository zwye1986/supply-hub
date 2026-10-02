package com.aifuli.module.supplychain.border.fulfillment.domain;

/** 恢复任务、安全故障及告警事件的稳定编码；日志不使用外部报文作为事件码。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿、#9-告警与观测。 */
public final class RechargeFrequencyRecoveryCodes {
    public static final String CHECKED = "FREQUENCY_CHECKED";
    public static final String INVALID_WAKE_PAYLOAD = "INVALID_FREQUENCY_WAKE_PAYLOAD";
    public static final String RECOVERY_FAILED = "FREQUENCY_RECOVERY_FAILED";
    public static final String QUERY_FAILED = "FREQUENCY_QUERY_FAILED";
    public static final String BACKLOG_ACTIVE = "BACKLOG_ACTIVE";
    public static final String BACKLOG_RECOVERED = "BACKLOG_RECOVERED";
    public static final String OCCUPIED_ACTIVE = "OCCUPIED_ACTIVE";
    public static final String OCCUPIED_RECOVERED = "OCCUPIED_RECOVERED";
    public static final String STORAGE_FAILED = "STORAGE_FAILED";
    public static final String STORAGE_RECOVERED = "STORAGE_RECOVERED";
    private RechargeFrequencyRecoveryCodes() { }
}
