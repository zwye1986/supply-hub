package com.aifuli.module.supplychain.supplieradapter.metadata;

/**
 * 结果多重性封闭枚举：单次下单最终产出的履约结果条目数语义。
 *
 * <p>发布校验与前端下拉的单一来源。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#51-adaptermetadata-扩展}。</p>
 */
public enum ResultMultiplicity {
    /** 无结果条目（如纯动作类）。 */
    NONE,
    /** 单一结果条目（如单张卡密）。 */
    SINGLE,
    /** 多结果条目（如批量卡密）。 */
    MULTIPLE;

    public String code() {
        return name();
    }

    public static ResultMultiplicity fromCode(String code) {
        for (ResultMultiplicity value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown result multiplicity: " + code);
    }
}
