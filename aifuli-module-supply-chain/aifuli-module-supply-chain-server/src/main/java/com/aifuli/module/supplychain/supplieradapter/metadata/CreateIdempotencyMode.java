package com.aifuli.module.supplychain.supplieradapter.metadata;

/**
 * 下单幂等模式封闭枚举：重复下单请求的去重/可重放语义。
 *
 * <p>与 {@link CompletionMode} 同为发布校验与前端下拉的单一来源。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#51-adaptermetadata-扩展}。</p>
 */
public enum CreateIdempotencyMode {
    /** 供应商不保证幂等，平台侧不得重放。 */
    NONE,
    /** 供应商按幂等键去重，重复请求返回首次结果但不保证可重放取结果。 */
    DEDUP_ONLY,
    /** 供应商支持以同一幂等键重放并取回既有结果（REPLAY_SAME_KEY 策略前置条件）。 */
    REPLAYABLE_RESULT;

    public String code() {
        return name();
    }

    public static CreateIdempotencyMode fromCode(String code) {
        for (CreateIdempotencyMode value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown create idempotency mode: " + code);
    }
}
