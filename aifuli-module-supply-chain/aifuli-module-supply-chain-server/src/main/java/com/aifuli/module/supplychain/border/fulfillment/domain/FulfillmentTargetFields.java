package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 履约目标封闭字段名与取值，供 Schema 校验与 DTO 注释对齐。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public final class FulfillmentTargetFields {

    private FulfillmentTargetFields() {
    }

    public static final String ACCOUNT_TYPE = "accountType";
    public static final String ACCOUNT_VALUE = "accountValue";
    public static final String DELIVERY_MODE = "deliveryMode";

    public static final String ACCOUNT_TYPE_MOBILE = "MOBILE";
    public static final String ACCOUNT_TYPE_EXTERNAL_ACCOUNT = "EXTERNAL_ACCOUNT";
    public static final String DELIVERY_MODE_BUYER_ASSET_QUERY = "BUYER_ASSET_QUERY";

    /** 履约目标 HMAC 指纹用途码。 */
    public static final String FINGERPRINT_PURPOSE = "FULFILLMENT_TARGET";
}
