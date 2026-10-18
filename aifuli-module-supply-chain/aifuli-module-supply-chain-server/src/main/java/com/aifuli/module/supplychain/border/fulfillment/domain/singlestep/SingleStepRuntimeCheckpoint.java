package com.aifuli.module.supplychain.border.fulfillment.domain.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepSupplierFact;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * V3认证运行信封；原V1冻结内容不变，成功证据独立提交且不随进度更新丢失。
 * 不含手机号或秘密；设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#76-t04公共终态与资金切片2026-10-18。
 */
public record SingleStepRuntimeCheckpoint(int schemaVersion, SingleStepCheckpoint frozen, Progress progress,
                                          ResultEvidence successEvidence, ResultEvidence terminalEvidence) {
    public static final int LEGACY_SCHEMA_VERSION = 2;
    public static final int SCHEMA_VERSION = 3;

    public SingleStepRuntimeCheckpoint(int schemaVersion, SingleStepCheckpoint frozen, Progress progress) {
        this(schemaVersion, frozen, progress, null, null);
    }

    public SingleStepRuntimeCheckpoint {
        if (schemaVersion != SCHEMA_VERSION || frozen == null || progress == null
                || successEvidence != null && (!successEvidence.fact().binding().equals(frozen.binding())
                    || successEvidence.fact().observation() != SingleStepSupplierFact.Observation.SUCCESS || !progress.trustedExists())
                || terminalEvidence != null && !terminalEvidence.fact().binding().equals(frozen.binding())) {
            throw new IllegalArgumentException("单步骤运行检查点无效");
        }
    }

    /** 进度更新不得清除已持久成功/终态证据，配置摘要仍仅覆盖frozen。 */
    public SingleStepRuntimeCheckpoint withProgress(Progress next) {
        return new SingleStepRuntimeCheckpoint(SCHEMA_VERSION, frozen, next, successEvidence, terminalEvidence);
    }

    public record ResultEvidence(long generation, SingleStepSupplierFact fact) {
        public ResultEvidence {
            if (generation <= 0 || fact == null || fact.verification() != SingleStepSupplierFact.Verification.VERIFIED
                    || fact.reportedSettlementAmount() != null && fact.reportedSettlementAmount() < 0) {
                throw new IllegalArgumentException("单步骤终态证据无效");
            }
        }
        @Override public String toString() { return "SingleStepResultEvidence[redacted]"; }
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
        LOCAL_PREPARATION_FAILED, BUDGET_EXHAUSTED, DEADLINE_REACHED,
        TERMINAL_CONFLICT, UNCONFIRMED_FAILURE, FUNDS_CONFLICT, NOT_FOUND_EXHAUSTED
    }

    @Override public String toString() { return "SingleStepRuntimeCheckpoint[redacted]"; }
}
