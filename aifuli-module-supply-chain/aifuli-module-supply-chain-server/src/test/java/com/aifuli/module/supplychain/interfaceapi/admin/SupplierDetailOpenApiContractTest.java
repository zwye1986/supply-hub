package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService.SupplierDetail;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商详情 OpenAPI 契约，防止 Knife4j 响应模型只显示字段名而缺少业务说明。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#52-详情--新增--编辑}。</p>
 */
class SupplierDetailOpenApiContractTest {

    @Test
    void documentsEverySupplierDetailPropertyForSwagger() {
        Schema<?> schema = resolveSchema(SupplierDetail.class, "SupplierDetail");
        Map<String, Schema> properties = schema.getProperties();

        assertThat(schema.getDescription()).isEqualTo("供应商详情");
        assertThat(properties.get("spuCount").getDescription())
                .isEqualTo("未退役 Offer 关联的平台 SPU 去重数");
        assertThat(properties).doesNotContainKey("skuCount");
        assertThat(properties.get("scopeCount").getDescription()).isEqualTo("供货配置数量");
        assertThat(properties.get("settlementBalanceCents").getDescription())
                .isEqualTo("供应商预存可用额度，单位为分，只读");
        assertThat(properties.get("onboardAt").getDescription()).isEqualTo("接入时间，UTC");

        properties.forEach((name, property) ->
                assertThat(property.getDescription())
                        .as("OpenAPI description for SupplierDetail.%s", name)
                        .isNotBlank());
    }

    @Test
    void documentsEverySupplierContactPropertyForSwagger() {
        Schema<?> schema = resolveSchema(ContactLine.class, "ContactLine");
        Map<String, Schema> properties = schema.getProperties();

        assertThat(schema.getDescription()).isEqualTo("供应商运营联系人");
        assertThat(properties).containsKeys("name", "mobile", "email");
        properties.forEach((name, property) ->
                assertThat(property.getDescription())
                        .as("OpenAPI description for ContactLine.%s", name)
                        .isNotBlank());
    }

    @SuppressWarnings("rawtypes")
    private static Schema<?> resolveSchema(Class<?> type, String preferredName) {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(type);
        Schema<?> named = schemas.get(preferredName);
        if (named != null) {
            return named;
        }
        return schemas.values().stream()
                .filter(schema -> schema.getProperties() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未生成 " + preferredName + " schema: " + schemas.keySet()));
    }
}
