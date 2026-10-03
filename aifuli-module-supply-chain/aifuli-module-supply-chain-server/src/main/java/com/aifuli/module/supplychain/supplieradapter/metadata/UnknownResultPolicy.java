package com.aifuli.module.supplychain.supplieradapter.metadata;

/**
 * 结果未知处置策略封闭枚举：下单返回 RESULT_UNKNOWN 时的兜底动作。
 *
 * <p>{@link #REPLAY_SAME_KEY} 要求 {@link CreateIdempotencyMode#REPLAYABLE_RESULT}，
 * 该跨字段约束在发布校验中强制。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#51-adaptermetadata-扩展}。</p>
 */
public enum UnknownResultPolicy {
    /** 主动查单确认结果。 */
    QUERY_ORDER,
    /** 等待供应商回调确认结果。 */
    WAIT_CALLBACK,
    /** 以同一幂等键重放下单取回既有结果（须 REPLAYABLE_RESULT）。 */
    REPLAY_SAME_KEY,
    /** 转人工处置。 */
    MANUAL;

    public String code() {
        return name();
    }

    public static UnknownResultPolicy fromCode(String code) {
        for (UnknownResultPolicy value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown result policy: " + code);
    }
}
