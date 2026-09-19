package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Builder;

/**
 * 供应商单次网络调用传输审计摘要，用于后续持久化至 {@code sc_fulfillment_call_log}。
 *
 * <p>CallLog 仅追加记录网络尝试摘要与耗时，不保存明文凭证或可用于重放的明文卡密上下文。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
@Builder
public record CallDigestFact(
        String endpointCode,
        long durationMs,
        Integer httpStatus,
        byte[] requestDigest,
        byte[] responseDigest,
        String resultClass,
        String traceId
) {
    public CallDigestFact {
        requestDigest = requestDigest == null ? null : requestDigest.clone();
        responseDigest = responseDigest == null ? null : responseDigest.clone();
        if (durationMs < 0) {
            durationMs = 0;
        }
    }

    @Override
    public byte[] requestDigest() {
        return requestDigest == null ? null : requestDigest.clone();
    }

    @Override
    public byte[] responseDigest() {
        return responseDigest == null ? null : responseDigest.clone();
    }
}
