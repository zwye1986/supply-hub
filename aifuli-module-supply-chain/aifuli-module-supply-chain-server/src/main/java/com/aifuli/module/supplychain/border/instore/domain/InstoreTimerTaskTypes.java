package com.aifuli.module.supplychain.border.instore.domain;

/**
 * 到店购 PersistentTimer 任务类型。
 *
 * <p>用于超时查单与可恢复操作的重入。详见
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#4-中台侧场景详情模块}。</p>
 */
public final class InstoreTimerTaskTypes {

    private InstoreTimerTaskTypes() {
    }

    /** 到店购操作恢复：用原 outTradeNo/trade_id/invocationId + expectedVersion 重新进入 Lifecycle。 */
    public static final String INSTORE_OPERATION_RECOVER = "INSTORE_OPERATION_RECOVER";
}
