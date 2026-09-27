package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.exception.ServiceException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUSINESS_CODE_MANUAL_NOT_ALLOWED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理端创建请求出现系统分配的编码字段时，必须返回稳定业务错误，包含显式 null 与空串。
 * 设计见 docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#6-接口前端与错误提示。
 */
class BusinessCodeAdminCreateContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void supplierCreateRejectsExplicitNullCode() throws Exception {
        var request = mapper.readValue("{\"supplierCode\":null}",
                SupplierAdminController.SupplierSaveReqVO.class);
        assertThatThrownBy(() -> new SupplierAdminController(null, null, null, null, null, null)
                .create(request))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUSINESS_CODE_MANUAL_NOT_ALLOWED.getCode());
    }

    @Test
    void buyerCreateRejectsExplicitEmptyCode() throws Exception {
        var request = mapper.readValue("{\"buyerCode\":\"\"}",
                BuyerAdminController.BuyerSaveReqVO.class);
        assertThatThrownBy(() -> new BuyerAdminController(null, null, null, null, null, null)
                .create(request))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUSINESS_CODE_MANUAL_NOT_ALLOWED.getCode());
    }

    @Test
    void projectCreateRejectsManualCode() throws Exception {
        var request = mapper.readValue("{\"projectCode\":\"PRJ_001_001\"}",
                ProjectAdminController.ProjectSaveReqVO.class);
        assertThatThrownBy(() -> new ProjectAdminController(null, null, null, null, null)
                .create(request))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUSINESS_CODE_MANUAL_NOT_ALLOWED.getCode());
    }
}
