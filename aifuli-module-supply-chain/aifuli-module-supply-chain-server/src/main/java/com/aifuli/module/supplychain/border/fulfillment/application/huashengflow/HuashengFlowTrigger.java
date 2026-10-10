package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇单步骤执行触发源。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#51-锁序与-permit。
 */
public enum HuashengFlowTrigger {
    /** 首次执行 / 唯一下一步执行。 */
    EXECUTE,
    /** 租约到期或未知结果恢复。 */
    RECOVER,
    /** 租约 watchdog 触发。 */
    WATCHDOG,
    /** 准备阶段 watchdog 触发。 */
    PREPARATION_WATCHDOG,
    /** 外部异步可信回调触发。 */
    CALLBACK
}
