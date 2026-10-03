package com.aifuli.module.supplychain.supplieradapter.metadata;

/**
 * 履约完成模式封闭枚举：下单后如何获知最终结果。
 *
 * <p>后端发布校验（{@code SupplierOfferPublicationService.validateCommercialTerms}）与前端下拉
 * （T1 GET {@code /adapter-types}）共用此枚举，消除前后端漂移。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#51-adaptermetadata-扩展}。</p>
 */
public enum CompletionMode {
    /** 同步返回即终态。 */
    SYNCHRONOUS,
    /** 需主动查单获知结果。 */
    QUERY,
    /** 依赖供应商回调获知结果。 */
    CALLBACK,
    /** 查单或回调任一先到即终态。 */
    QUERY_OR_CALLBACK;

    public String code() {
        return name();
    }

    public static CompletionMode fromCode(String code) {
        for (CompletionMode value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown completion mode: " + code);
    }
}
