package com.aifuli.module.supplychain.border.fulfillment.domain.singlestep;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 已提交认领的只读资格；单调截止仅本进程有效，绝不持久化或跨实例传递。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#47-t03本地执行与恢复接线2026-10-18。
 */
public record SingleStepExecutionPermit(SingleStepCheckpoint.Binding binding, SingleStepAction action,
                                      long generation, LocalDateTime leaseUntil, long deadlineNanos) {
    public SingleStepExecutionPermit {
        Objects.requireNonNull(binding); Objects.requireNonNull(action); Objects.requireNonNull(leaseUntil);
        if (generation <= 0) throw new IllegalArgumentException("单步骤认领代次无效");
    }
    public String operationNo() { return binding.operationNo(); }
}
