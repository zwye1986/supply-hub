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

    /** 新单步骤同代补查及执行租约看门狗，详见三家详细设计§4.7。 */
    public static final String SINGLE_STEP_QUERY = "SINGLE_STEP_QUERY";
    public static final String SINGLE_STEP_WATCHDOG = "SINGLE_STEP_WATCHDOG";
    /** 商户容量等待独立序次，不改供应商补查事实；详见商户共享限额设计§7。 */
    public static final String SINGLE_STEP_CAPACITY_WAKE = "SINGLE_STEP_CAPACITY_WAKE";

    /** 新单步骤受理事务的准备定位任务。
     * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#43-查询看门狗与频控协作。 */
    public static final String SINGLE_STEP_PREPARATION_WATCHDOG = "SINGLE_STEP_PREPARATION_WATCHDOG";
    public static final int SINGLE_STEP_PREPARATION_WATCHDOG_MINUTES = 5;

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
