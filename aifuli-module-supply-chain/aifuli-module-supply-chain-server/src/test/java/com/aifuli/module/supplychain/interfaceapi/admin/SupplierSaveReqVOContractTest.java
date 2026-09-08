package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.interfaceapi.admin.SupplierAdminController.SupplierSaveReqVO;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商档案保存请求的 OpenAPI 字段说明须与 DDL COMMENT 对齐，避免 Knife4j 参数说明空白。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#153-新增编辑表单}。</p>
 */
class SupplierSaveReqVOContractTest {

    @Test
    void documentsEverySaveRequestPropertyForSwagger() {
        Schema<?> schema = resolveSchema(SupplierSaveReqVO.class);
        Map<String, Schema> properties = schema.getProperties();

        assertThat(properties).doesNotContainKeys("resolvedSupplierName", "supplierNamePresent");
        assertThat(properties.get("supplierName").getDescription()).isEqualTo("供应商标准名称");
        assertThat(properties.get("name").getDescription())
                .isEqualTo("供应商标准名称（与 supplierName 同义，同时出现时以本字段为准）");
        assertThat(properties.get("deliveryRegionType").getDescription())
                .isEqualTo("配送区域：NATIONWIDE全国，DESIGNATED指定区域");
        assertThat(properties.get("deliveryRegionAdcodes").getDescription()).isEqualTo("国标行政区划代码");
        assertThat(properties.get("aftersalesSupported").getDescription()).isEqualTo("是否支持售后：1是，0否");
        assertThat(properties.get("settlementCycleDays").getDescription()).isEqualTo("结算周期天数");
        assertThat(properties.get("onboardAt").getDescription()).isEqualTo("接入时间，UTC");
        assertThat(properties.get("cooperationNote").getDescription()).isEqualTo("合作说明");
        assertThat(properties.get("legalEntityName").getDescription()).isEqualTo("供应商签约法律主体名称");
        assertThat(properties.get("creditCode").getDescription()).isEqualTo("统一社会信用代码");
        assertThat(properties.get("legalPersonName").getDescription()).isEqualTo("法人姓名");
        assertThat(properties.get("legalPersonIdNo").getDescription()).isEqualTo("法人身份证号");
        assertThat(properties.get("legalPersonMobile").getDescription()).isEqualTo("法人手机号");
        assertThat(properties.get("contactAddress").getDescription()).isEqualTo("联系地址");
        assertThat(properties.get("bankName").getDescription()).isEqualTo("开户行");
        assertThat(properties.get("bankAccount").getDescription()).isEqualTo("开户账号");
        assertThat(properties.get("licenseFileKey").getDescription()).isEqualTo("营业执照文件引用");
        assertThat(properties.get("idFrontFileKey").getDescription()).isEqualTo("法人身份证正面文件引用");
        assertThat(properties.get("idBackFileKey").getDescription()).isEqualTo("法人身份证反面文件引用");
        assertThat(properties.get("qualificationFileKey").getDescription()).isEqualTo("资质文件引用");
        assertThat(properties.get("openingBalanceCents").getDescription()).isEqualTo("预存可用额度，单位为分");
        assertThat(properties.get("contacts").getDescription()).isEqualTo("运营联系人");

        properties.forEach((name, property) ->
                assertThat(property.getDescription())
                        .as("OpenAPI description for %s", name)
                        .isNotBlank());
    }

    @SuppressWarnings("rawtypes")
    private static Schema<?> resolveSchema(Class<?> type) {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(type);
        Schema<?> named = schemas.get("SupplierSaveReqVO");
        if (named != null) {
            return named;
        }
        return schemas.values().stream()
                .filter(schema -> schema.getProperties() != null && schema.getProperties().containsKey("legalEntityName"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未生成 SupplierSaveReqVO schema: " + schemas.keySet()));
    }
}
