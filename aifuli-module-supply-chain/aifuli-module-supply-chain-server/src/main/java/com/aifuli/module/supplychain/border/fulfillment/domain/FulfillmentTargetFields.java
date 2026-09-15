package com.aifuli.module.supplychain.border.fulfillment.domain;

import java.util.Map;

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

    /** 订单可查询列 {@code fulfillment_account_value} 的最大长度，短于目标 Schema 的 256。 */
    public static final int MOBILE_ACCOUNT_VALUE_MAX_LENGTH = 128;

    /**
     * 从履约目标提取可计入绑定手机号限购的手机号；非 MOBILE、空白或超长时返回空。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。
     */
    public static String mobileAccountValue(Map<String, String> values) {
        if (values == null || !ACCOUNT_TYPE_MOBILE.equals(values.get(ACCOUNT_TYPE))) {
            return null;
        }
        String raw = values.get(ACCOUNT_VALUE);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MOBILE_ACCOUNT_VALUE_MAX_LENGTH) {
            return null;
        }
        return trimmed;
    }
}
