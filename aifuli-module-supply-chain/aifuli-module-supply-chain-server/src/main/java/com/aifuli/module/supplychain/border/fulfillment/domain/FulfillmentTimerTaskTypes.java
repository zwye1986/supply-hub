package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 标准履约 PersistentTimer 任务类型。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public final class FulfillmentTimerTaskTypes {

    private FulfillmentTimerTaskTypes() {
    }

    public static final String RECHARGE_FREQUENCY_WAKE = "RECHARGE_FREQUENCY_WAKE";

    public static final String OPERATION_EXECUTE = "FULFILLMENT_OPERATION_EXECUTE";

    /** 华昇唯一下一步与首次执行 Timer 任务类型。 */
    public static final String HUASHENG_EXECUTE = "HS_FULFILLMENT_EXECUTE";

    /** 华昇租约 watchdog Timer 任务类型。 */
    public static final String HUASHENG_LEASE_WATCHDOG = "HS_LEASE_WATCHDOG";

    /** 华昇准备阶段 watchdog Timer 任务类型。 */
    public static final String HUASHENG_PREPARATION_WATCHDOG = "HS_PREPARATION_WATCHDOG";

    /** 华昇即时/定时恢复 Timer 任务类型。 */
    public static final String HUASHENG_RECOVERY = "HS_RECOVERY";

    /** 华昇人工 Case 原单核实 Timer 任务类型。 */
    public static final String HUASHENG_CASE_VERIFY = "HS_CASE_VERIFY";

    /** 华昇人工 Case 核实租约到期收尾 Timer 任务类型。 */
    public static final String HUASHENG_CASE_VERIFY_FINALIZE = "HS_CASE_VERIFY_FINALIZE";
}
