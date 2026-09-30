package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 供应商原始状态观察事实，用于后续持久化至 {@code sc_raw_status_observation}。
 *
 * <p>对应追加式原始状态审计事实，记录供应商在创建响应、回调或查单中报告的原生状态码。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
@Builder
public record RawObservationFact(
        String sourceType,
        String externalOrderNo,
        String externalStatus,
        String externalSubStatus,
        byte[] payloadDigest,
        String rawPayload,
        LocalDateTime observedAt
) {
    public RawObservationFact {
        payloadDigest = payloadDigest == null ? null : payloadDigest.clone();
        if (externalSubStatus == null) {
            externalSubStatus = "";
        }
        if (observedAt == null) {
            observedAt = LocalDateTime.now();
        }
    }

    @Override
    public byte[] payloadDigest() {
        return payloadDigest == null ? null : payloadDigest.clone();
    }
}
