package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 履约目标封闭 Schema 校验：禁止未知键、供应商私有参数与非法取值。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
class FulfillmentTargetSchemaRegistryTest {

    private final FulfillmentTargetSchemaRegistry registry = new FulfillmentTargetSchemaRegistry();

    @Test
    void acceptsDirectRechargeMobileTarget() {
        assertThatCode(() -> registry.validate("DIRECT_RECHARGE_TARGET", 1, Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, "13800000000")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsVoucherDeliveryTarget() {
        assertThatCode(() -> registry.validate("VOUCHER_DELIVERY_TARGET", 1, Map.of(
                FulfillmentTargetFields.DELIVERY_MODE,
                FulfillmentTargetFields.DELIVERY_MODE_BUYER_ASSET_QUERY)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownSchemaOrVersion() {
        Map<String, String> values = Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, "13800000000");
        assertThatThrownBy(() -> registry.validate("UNKNOWN_TARGET", 1, values))
                .isInstanceOf(FulfillmentTargetSchemaRegistry.InvalidTargetException.class);
        assertThatThrownBy(() -> registry.validate("DIRECT_RECHARGE_TARGET", 2, values))
                .isInstanceOf(FulfillmentTargetSchemaRegistry.InvalidTargetException.class);
    }

    @Test
    void rejectsSupplierPrivateOrUrlKeys() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE);
        values.put(FulfillmentTargetFields.ACCOUNT_VALUE, "13800000000");
        values.put("callbackUrl", "https://supplier.example/notify");
        assertThatThrownBy(() -> registry.validate("DIRECT_RECHARGE_TARGET", 1, values))
                .isInstanceOf(FulfillmentTargetSchemaRegistry.InvalidTargetException.class);
    }

    @Test
    void rejectsBlankOrOversizedAccountValue() {
        assertThatThrownBy(() -> registry.validate("DIRECT_RECHARGE_TARGET", 1, Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, " ")))
                .isInstanceOf(FulfillmentTargetSchemaRegistry.InvalidTargetException.class);
        assertThatThrownBy(() -> registry.validate("DIRECT_RECHARGE_TARGET", 1, Map.of(
                FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
                FulfillmentTargetFields.ACCOUNT_VALUE, "a".repeat(257))))
                .isInstanceOf(FulfillmentTargetSchemaRegistry.InvalidTargetException.class);
    }
}
