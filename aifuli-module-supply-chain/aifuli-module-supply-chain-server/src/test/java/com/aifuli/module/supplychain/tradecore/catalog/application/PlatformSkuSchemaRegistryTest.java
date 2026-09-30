package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuSchemaRegistry.SchemaDefinition;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台 SKU 履约 Schema 映射单测。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d11）。
 */
class PlatformSkuSchemaRegistryTest {

    private final PlatformSkuSchemaRegistry registry =
            new PlatformSkuSchemaRegistry(new FulfillmentTargetSchemaRegistry());

    @Test
    void mapsEveryFulfillmentTypeToClosedSchemaVersionOne() {
        assertSchema(FulfillmentType.DIRECT_RECHARGE,
                "DIRECT_RECHARGE_TARGET", "DIRECT_RECHARGE_RESULT");
        assertSchema(FulfillmentType.VOUCHER_CODE,
                "VOUCHER_DELIVERY_TARGET", "VOUCHER_CODE_RESULT");
        assertSchema(FulfillmentType.CARD_CODE_SECRET,
                "VOUCHER_DELIVERY_TARGET", "CARD_CODE_SECRET_RESULT");
        assertSchema(FulfillmentType.VOUCHER_URL,
                "VOUCHER_DELIVERY_TARGET", "VOUCHER_URL_RESULT");
        assertSchema(FulfillmentType.ACCOUNT_BOUND_BENEFIT,
                "ACCOUNT_BOUND_BENEFIT_TARGET", "ACCOUNT_BOUND_BENEFIT_RESULT");
    }

    private void assertSchema(FulfillmentType fulfillmentType, String targetCode, String resultCode) {
        SchemaDefinition schema = registry.requireByFulfillmentType(fulfillmentType);
        assertThat(schema.targetSchemaCode()).isEqualTo(targetCode);
        assertThat(schema.targetSchemaVersion()).isEqualTo(1);
        assertThat(schema.resultSchemaCode()).isEqualTo(resultCode);
        assertThat(schema.resultSchemaVersion()).isEqualTo(1);
    }
}
