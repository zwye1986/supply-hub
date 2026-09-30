package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.supply.application.SupplierDailyQuotaAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端自然日供货配额 HTTP 路径、鉴权和 JSON 字段契约。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#4-每日累计配额与并发}。
 */
class SupplierDailyQuotaAdminContractTest {

    @Test
    void exposesProtectedGetPutDeleteOnSingleResource() throws Exception {
        Class<SupplierDailyQuotaAdminController> controller = SupplierDailyQuotaAdminController.class;
        assertThat(controller.getAnnotation(RequestMapping.class).value())
                .containsExactly("/supply-chain/supplier-config/daily-quotas");
        assertThat(controller.getDeclaredMethod("get", String.class, String.class)
                .getAnnotation(GetMapping.class)).isNotNull();
        assertThat(controller.getDeclaredMethod("put", SupplierDailyQuotaAdminController.PutQuotaReqVO.class)
                .getAnnotation(PutMapping.class)).isNotNull();
        assertThat(controller.getDeclaredMethod("delete", String.class, String.class)
                .getAnnotation(DeleteMapping.class)).isNotNull();
        for (String methodName : new String[]{"get", "put", "delete"}) {
            assertThat(java.util.Arrays.stream(controller.getDeclaredMethods())
                    .filter(method -> method.getName().equals(methodName))
                    .findFirst().orElseThrow().getAnnotation(PreAuthorize.class)).isNotNull();
        }
    }

    @Test
    void serializesDailyLimitAndConfigurationIdentity() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SupplierDailyQuotaAdminController.PutQuotaReqVO request =
                new SupplierDailyQuotaAdminController.PutQuotaReqVO();
        request.setSupplierCode("SUPPLIER_A");
        request.setSkuCode("SKU_A");
        request.setDailyLimit(100);
        assertThat(mapper.writeValueAsString(request))
                .contains("\"supplierCode\":\"SUPPLIER_A\"")
                .contains("\"skuCode\":\"SKU_A\"")
                .contains("\"dailyLimit\":100");
        assertThat(mapper.writeValueAsString(new SupplierDailyQuotaAdminService.QuotaView(
                "SUPPLIER_A", "SKU_A", null, LocalDateTime.of(2026, 9, 24, 10, 0))))
                .contains("\"dailyLimit\":null");
    }
}
