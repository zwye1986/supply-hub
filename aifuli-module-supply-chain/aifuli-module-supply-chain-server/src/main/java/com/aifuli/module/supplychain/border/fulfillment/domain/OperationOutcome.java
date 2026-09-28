package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Builder;

import java.util.List;
import java.util.Objects;

/**
 * 履约操作标准化输出结果。
 *
 * <p>Adapter 仅产出本类型化事实，不拥有或修改数据库订单、状态机或账本。
 * 状态机推进由领域服务 {@code FulfillmentLifecycle} 在事务中统一收敛。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#731-operationexecutorport-契约模型}。</p>
 */
@Builder
public record OperationOutcome(
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
    public OperationOutcome {
        Objects.requireNonNull(outcomeClass, "outcomeClass is required");
        assets = assets == null ? List.of() : assets.stream().filter(Objects::nonNull).toList();
        nextCheckpoint = nextCheckpoint == null ? null : nextCheckpoint.clone();
    }

    @Override
    public byte[] nextCheckpoint() {
        return nextCheckpoint == null ? null : nextCheckpoint.clone();
    }
}
