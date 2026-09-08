package com.aifuli.module.supplychain.border.o2o.domain;

/**
 * O2O PersistentTimer 任务类型。
 *
 * <p>用于超时查单与可恢复操作的重入。详见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
 */
public final class O2OTimerTaskTypes {

    private O2OTimerTaskTypes() {
    }

    /** O2O 操作恢复：用原 outTradeNo/invocationId + expectedVersion 重新进入 Lifecycle。 */
    public static final String O2O_OPERATION_RECOVER = "O2O_OPERATION_RECOVER";
}
