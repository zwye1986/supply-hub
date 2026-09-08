package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetSchemaCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 平台履约目标的封闭 Schema；禁止任意 URL、Header 或供应商私有参数。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Component
public final class FulfillmentTargetSchemaRegistry {

    private static final Set<String> ACCOUNT_TYPES = Set.of(
            FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE,
            FulfillmentTargetFields.ACCOUNT_TYPE_EXTERNAL_ACCOUNT);

    public void validate(String schemaCode, int schemaVersion, Map<String, String> values) {
        if (schemaVersion != 1 || values == null) {
            throw new InvalidTargetException();
        }
        FulfillmentTargetSchemaCode schema;
        try {
            schema = FulfillmentTargetSchemaCode.valueOf(schemaCode);
        } catch (IllegalArgumentException exception) {
            throw new InvalidTargetException();
        }
        switch (schema) {
            case DIRECT_RECHARGE_TARGET, ACCOUNT_BOUND_BENEFIT_TARGET -> {
                requireExactKeys(values, Set.of(
                        FulfillmentTargetFields.ACCOUNT_TYPE, FulfillmentTargetFields.ACCOUNT_VALUE));
                if (!ACCOUNT_TYPES.contains(values.get(FulfillmentTargetFields.ACCOUNT_TYPE))
                        || values.get(FulfillmentTargetFields.ACCOUNT_VALUE).isBlank()
                        || values.get(FulfillmentTargetFields.ACCOUNT_VALUE).length() > 256) {
                    throw new InvalidTargetException();
                }
            }
            case VOUCHER_DELIVERY_TARGET -> {
                requireExactKeys(values, Set.of(FulfillmentTargetFields.DELIVERY_MODE));
                if (!FulfillmentTargetFields.DELIVERY_MODE_BUYER_ASSET_QUERY
                        .equals(values.get(FulfillmentTargetFields.DELIVERY_MODE))) {
                    throw new InvalidTargetException();
                }
            }
        }
    }

    private static void requireExactKeys(Map<String, String> values, Set<String> expected) {
        if (!values.keySet().equals(expected) || values.values().stream().anyMatch(String::isBlank)) {
            throw new InvalidTargetException();
        }
    }

    public static final class InvalidTargetException extends IllegalArgumentException {
        public InvalidTargetException() {
            super("Fulfillment target does not match a closed platform Schema");
        }
    }
}
