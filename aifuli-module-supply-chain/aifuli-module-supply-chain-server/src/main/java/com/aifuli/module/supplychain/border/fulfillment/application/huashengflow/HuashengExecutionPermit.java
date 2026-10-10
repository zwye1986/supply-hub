package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 华昇单次出站执行资格（不可变 permit）。
 * 不携带 DO 可变实体，锁后重核全部关联、activeBOrderId、活动标志及配置身份。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#51-锁序与-permit。
 */
public record HuashengExecutionPermit(
        String operationNo,
        long bOrderId,
        long executionGeneration,
        AdapterStepCode step,
        HuashengFlowTrigger trigger,
        LocalDateTime leaseUntil
) {
    public HuashengExecutionPermit {
        Objects.requireNonNull(operationNo, "operationNo is required");
        if (operationNo.isBlank()) {
            throw new IllegalArgumentException("operationNo must not be blank");
        }
        if (bOrderId <= 0) {
            throw new IllegalArgumentException("bOrderId must be positive");
        }
        if (executionGeneration < 0) {
            throw new IllegalArgumentException("executionGeneration must be non-negative");
        }
        Objects.requireNonNull(step, "step is required");
        Objects.requireNonNull(trigger, "trigger is required");
        Objects.requireNonNull(leaseUntil, "leaseUntil is required");
    }

    @Override
    public String toString() {
        return "HuashengExecutionPermit[operationNo=" + operationNo + ", bOrderId=" + bOrderId
                + ", gen=" + executionGeneration + ", step=" + step + ", trigger=" + trigger + "]";
    }
}
