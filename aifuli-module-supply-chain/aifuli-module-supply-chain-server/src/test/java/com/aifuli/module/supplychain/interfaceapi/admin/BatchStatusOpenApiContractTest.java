package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.BuyerBatchStatusReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.SupplierAdminController.SupplierBatchStatusReqVO;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量状态请求必须生成资源专属的 OpenAPI schema，避免分销商与供应商字段因简单类名相同而互相覆盖。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。</p>
 */
class BatchStatusOpenApiContractTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void generatesDistinctBuyerAndSupplierRequestSchemas() {
        Map<String, Schema> schemas = new LinkedHashMap<>();
        schemas.putAll(ModelConverters.getInstance().readAll(BuyerBatchStatusReqVO.class));
        schemas.putAll(ModelConverters.getInstance().readAll(SupplierBatchStatusReqVO.class));

        assertThat(schemas).containsKeys("BuyerBatchStatusReqVO", "SupplierBatchStatusReqVO");
        assertThat(schemas.get("BuyerBatchStatusReqVO").getProperties())
                .containsKeys("buyerCodes", "status")
                .doesNotContainKeys("supplierCodes", "targetStatus");
        assertThat(schemas.get("SupplierBatchStatusReqVO").getProperties())
                .containsKeys("supplierCodes", "status", "targetStatus")
                .doesNotContainKey("buyerCodes");
    }
}
