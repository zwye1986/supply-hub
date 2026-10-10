package com.aifuli.module.supplychain.border.fulfillment.domain;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;

import java.util.List;
import java.util.Objects;

/**
 * 履约操作标准化输出结果。
 *
 * <p>Adapter 仅产出本类型化事实，不拥有或修改数据库订单、状态机或账本。
 * 状态机推进由领域服务 {@code FulfillmentLifecycle} 在事务中统一收敛。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#731-operationexecutorport-契约模型}
 * 以及 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#31-边界与责任矩阵}。</p>
 */
@Builder
@JsonSerialize(using = FulfillmentInternalOnlySerializer.class)
public record OperationOutcome(
        OperationOutcomeClass outcomeClass,
        String externalOrderNo,
        Long reportedSettlementAmount,
        String externalErrorCode,
        String externalErrorMessage,
        List<OperationAssetFact> assets,
        RawObservationFact rawObservation,
        CallDigestFact callDigest,
        byte[] nextCheckpoint,
        FulfillmentProtocolFact protocolFact
) {
    /** 历史9参数构造兼容入口。 */
    public OperationOutcome(
            OperationOutcomeClass outcomeClass,
            String externalOrderNo,
            Long reportedSettlementAmount,
            String externalErrorCode,
            String externalErrorMessage,
            List<OperationAssetFact> assets,
            RawObservationFact rawObservation,
            CallDigestFact callDigest,
            byte[] nextCheckpoint
    ) {
        this(outcomeClass, externalOrderNo, reportedSettlementAmount, externalErrorCode, externalErrorMessage,
                assets, rawObservation, callDigest, nextCheckpoint, null);
    }

    public OperationOutcome {
        Objects.requireNonNull(outcomeClass, "outcomeClass is required");
        assets = assets == null ? List.of() : assets.stream().filter(Objects::nonNull).toList();
        nextCheckpoint = nextCheckpoint == null ? null : nextCheckpoint.clone();
    }

    @Override
    public byte[] nextCheckpoint() {
        return nextCheckpoint == null ? null : nextCheckpoint.clone();
    }

    @Override
    public String toString() {
        return "OperationOutcome[outcomeClass=" + outcomeClass + ", externalOrderNo=" + externalOrderNo + "]";
    }

    @JsonSerialize(using = FulfillmentInternalOnlySerializer.class)
    public static class OperationOutcomeBuilder {
        @Override
        public String toString() {
            return "OperationOutcomeBuilder[redacted]";
        }
    }
}
