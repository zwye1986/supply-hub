package com.aifuli.module.supplychain.border.fulfillment.domain.singlestep;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * V2认证运行信封；frozen始终为原V1，progress不参与配置摘要，不含手机号或秘密。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#47-t03本地执行与恢复接线2026-10-18。
 */
public record SingleStepRuntimeCheckpoint(int schemaVersion, SingleStepCheckpoint frozen, Progress progress) {
    public static final int SCHEMA_VERSION = 2;

    public SingleStepRuntimeCheckpoint {
        if (schemaVersion != SCHEMA_VERSION || frozen == null || progress == null) {
            throw new IllegalArgumentException("单步骤运行检查点无效");
        }
    }

    public static SingleStepRuntimeCheckpoint initial(SingleStepCheckpoint frozen) {
        return new SingleStepRuntimeCheckpoint(SCHEMA_VERSION, frozen,
                new Progress(SingleStepAction.CREATE, 0, false, 0, Fact.NONE, null));
    }

    public record Progress(SingleStepAction action, long appliedGeneration, boolean trustedExists,
                           int consecutiveNotFound, Fact lastFact, LocalDateTime factAt) {
        public Progress {
            Objects.requireNonNull(action); Objects.requireNonNull(lastFact);
            if (appliedGeneration < 0 || consecutiveNotFound < 0 || consecutiveNotFound > 3
                    || (lastFact != Fact.NONE && factAt == null)) {
                throw new IllegalArgumentException("单步骤运行检查点无效");
            }
        }
    }

    public enum Fact {
        NONE, CLAIMED, ACCEPTED, PROCESSING, NOT_FOUND, NETWORK_UNKNOWN, RESPONSE_UNKNOWN,
        CREATE_LEASE_EXPIRED, QUERY_LEASE_EXPIRED, SECURITY_CONFLICT, TERMINAL_OBSERVED,
        LOCAL_PREPARATION_FAILED, BUDGET_EXHAUSTED, DEADLINE_REACHED
    }

    @Override public String toString() { return "SingleStepRuntimeCheckpoint[redacted]"; }
}
