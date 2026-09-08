package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.interfaceapi.admin.SupplierAdminController.BatchStatusReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.SupplierAdminController.ChangeStatusReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.SupplierAdminController.SupplierSaveReqVO;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新增页供应商名称与列表必须同一口径；状态变更 JSON 主字段为 status。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#151-合作状态}。</p>
 */
class SupplierAdminControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void prefersYudaoNameAsSupplierTitleWhenBothNameFieldsPresent() {
        SupplierSaveReqVO request = new SupplierSaveReqVO();
        request.setName("新增时填写的供应商名称");
        request.setSupplierName("被误填成供应商名称的公司名");
        request.setLegalEntityName("公司名称");
        assertThat(request.resolvedSupplierName()).isEqualTo("新增时填写的供应商名称");
    }

    @Test
    void fallsBackToSupplierNameWhenYudaoNameAbsent() {
        SupplierSaveReqVO request = new SupplierSaveReqVO();
        request.setSupplierName("只传了supplierName");
        assertThat(request.resolvedSupplierName()).isEqualTo("只传了supplierName");
    }

    @Test
    void batchStatusAcceptsFrontendStatusJson() throws Exception {
        BatchStatusReqVO request = MAPPER.readValue(
                "{\"supplierCodes\":[\"RT_MART_TEST\",\"SPL_01M1K7TT5P8CPXSD6BNSWTGWXH\"],\"status\":\"ACTIVE\"}",
                BatchStatusReqVO.class);
        assertThat(request.resolvedTargetStatus()).isEqualTo("ACTIVE");
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void batchStatusStillAcceptsLegacyTargetStatusJson() throws Exception {
        BatchStatusReqVO request = MAPPER.readValue(
                "{\"supplierCodes\":[\"RT_MART_TEST\"],\"targetStatus\":\"SUSPENDED\"}",
                BatchStatusReqVO.class);
        assertThat(request.resolvedTargetStatus()).isEqualTo("SUSPENDED");
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void batchStatusPrefersStatusWhenBothPresent() throws Exception {
        BatchStatusReqVO request = MAPPER.readValue(
                "{\"supplierCodes\":[\"RT_MART_TEST\"],\"status\":\"ACTIVE\",\"targetStatus\":\"SUSPENDED\"}",
                BatchStatusReqVO.class);
        assertThat(request.resolvedTargetStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void batchStatusRejectsMissingStatus() throws Exception {
        BatchStatusReqVO request = MAPPER.readValue(
                "{\"supplierCodes\":[\"RT_MART_TEST\"]}",
                BatchStatusReqVO.class);
        Set<ConstraintViolation<BatchStatusReqVO>> violations = VALIDATOR.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("不能为空"));
    }

    @Test
    void changeStatusAcceptsFrontendStatusJson() throws Exception {
        ChangeStatusReqVO request = MAPPER.readValue("{\"status\":\"BLACKLIST\"}", ChangeStatusReqVO.class);
        assertThat(request.resolvedTargetStatus()).isEqualTo("BLACKLIST");
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }
}
