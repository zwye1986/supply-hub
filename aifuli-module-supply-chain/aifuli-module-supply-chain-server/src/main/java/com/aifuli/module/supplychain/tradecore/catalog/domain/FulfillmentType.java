package com.aifuli.module.supplychain.tradecore.catalog.domain;

/**
 * 平台商品履约类型，对应 SKU / 路由规则 {@code fulfillment_type} DDL CHECK。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public enum FulfillmentType {
    DIRECT_RECHARGE,
    VOUCHER_CODE,
    CARD_CODE_SECRET,
    VOUCHER_URL,
    ACCOUNT_BOUND_BENEFIT;

    public String code() {
        return name();
    }

    public static FulfillmentType fromCode(String code) {
        for (FulfillmentType type : values()) {
            if (type.name().equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown fulfillment type: " + code);
    }

    /**
     * 绑定手机号限购仅适用于下单携带履约手机号的权益类型。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。
     */
    public boolean supportsPhoneBindLimit() {
        return this == DIRECT_RECHARGE || this == ACCOUNT_BOUND_BENEFIT;
    }
}
