package com.aifuli.module.supplychain.border.fulfillment.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 履约目标手机号提取：仅 MOBILE 且长度不超过订单可查询列。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。
 */
class FulfillmentTargetFieldsTest {

    @Test
    void extractsCanonicalMobileAccountValue() {
        assertThat(FulfillmentTargetFields.mobileAccountValue(Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, " 13900139000 ")))
                .isEqualTo("13900139000");
        assertThat(FulfillmentTargetFields.mobileAccountValue(Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, "+8613900139000")))
                .isEqualTo("13900139000");
        assertThat(FulfillmentTargetFields.normalizeMobile("008613900139000"))
                .isEqualTo("13900139000");
    }

    @Test
    void ignoresNonMobileOrOversizedAccountValue() {
        assertThat(FulfillmentTargetFields.mobileAccountValue(Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_EXTERNAL_ACCOUNT,
                FulfillmentTargetFields.ACCOUNT_VALUE, "13900139000"))).isNull();
        assertThat(FulfillmentTargetFields.mobileAccountValue(Map.of(
                FulfillmentTargetFields.DELIVERY_MODE, FulfillmentTargetFields.DELIVERY_MODE_BUYER_ASSET_QUERY)))
                .isNull();
        assertThat(FulfillmentTargetFields.mobileAccountValue(Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, "1".repeat(129)))).isNull();
        assertThat(FulfillmentTargetFields.normalizeMobile("member-1024")).isNull();
        assertThat(FulfillmentTargetFields.normalizeMobile("12800138000")).isNull();
        assertThat(FulfillmentTargetFields.mobileAccountValue(null)).isNull();
    }
}
