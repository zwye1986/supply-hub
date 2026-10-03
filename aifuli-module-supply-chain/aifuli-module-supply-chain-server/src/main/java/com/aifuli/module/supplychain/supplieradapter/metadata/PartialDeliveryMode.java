package com.aifuli.module.supplychain.supplieradapter.metadata;

/**
 * 部分履约模式封闭枚举：单次下单是否允许供应商部分成功。
 *
 * <p>发布校验与前端下拉的单一来源。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#51-adaptermetadata-扩展}。</p>
 */
public enum PartialDeliveryMode {
    /** 禁止部分履约，非全成即全败。 */
    FORBIDDEN,
    /** 允许部分履约。 */
    ALLOWED;

    public String code() {
        return name();
    }

    public static PartialDeliveryMode fromCode(String code) {
        for (PartialDeliveryMode value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown partial delivery mode: " + code);
    }
}
