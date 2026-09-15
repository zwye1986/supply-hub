package com.aifuli.module.supplychain.api.fulfillment;

import com.aifuli.module.supplychain.api.fulfillment.dto.AcceptFulfillmentReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentTargetDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标准履约受理 RPC 契约：校验注解、JSON 字段边界与禁止泄漏的内部标识。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#7.2-标准履约受理}。</p>
 */
class BuyerFulfillmentContractTest {

    private static Validator validator;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void acceptsValidRequest() {
        Set<ConstraintViolation<AcceptFulfillmentReqDTO>> violations =
                validator.validate(validRequest());
        assertThat(violations).isEmpty();
    }

    @Test
    void rejectsMissingRequiredFields() {
        AcceptFulfillmentReqDTO request = new AcceptFulfillmentReqDTO();
        Set<String> properties = validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(properties).contains(
                "sourceSystem",
                "externalTenantId",
                "projectCode",
                "buyerOrderNo",
                "buyerUserId",
                "buyerSkuCode",
                "listingVersion",
                "quantity",
                "target",
                "paidAt");
    }

    @Test
    void serializesWithoutInternalIdentifiersOrForbiddenTypes() throws Exception {
        String json = objectMapper.writeValueAsString(validRequest());
        JsonNode root = objectMapper.readTree(json);

        assertThat(collectFieldNames(root)).noneMatch(name ->
                name.equals("buyerId")
                        || name.equals("supplierId")
                        || name.equals("POINT")
                        || name.equals("BigDecimal"));
        assertThat(json).doesNotContain("buyerId", "supplierId", "BigDecimal", "\"POINT\"");
        assertThat(root.get("quantity").isIntegralNumber()).isTrue();
        assertThat(root.get("listingVersion").isIntegralNumber()).isTrue();
        assertThat(root.has("paidAt")).isTrue();
        assertThat(root.get("target").fieldNames()).toIterable().containsExactly("values");
    }

    @Test
    void rejectsEmptyTargetValues() {
        AcceptFulfillmentReqDTO request = validRequest();
        request.getTarget().setValues(Map.of());

        Set<ConstraintViolation<AcceptFulfillmentReqDTO>> violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("target.values"));
    }

    @Test
    void receiptContractExposesStableRejectionCode() {
        assertThat(java.util.Arrays.stream(FulfillmentAcceptanceReceiptRespDTO.class.getDeclaredFields())
                .map(field -> field.getName()))
                .contains("rejectionCode", "receiptStatus", "receiptVersion");
    }

    private static AcceptFulfillmentReqDTO validRequest() {
        FulfillmentTargetDTO target = new FulfillmentTargetDTO();
        target.setValues(Map.of("accountType", "MOBILE", "accountValue", "13800000000"));

        AcceptFulfillmentReqDTO request = new AcceptFulfillmentReqDTO();
        request.setSourceSystem("AIFULI_MALL");
        request.setExternalTenantId("tenant-wusheng");
        request.setProjectCode("PRJ_01ABC");
        request.setBuyerOrderNo("C202608110001");
        request.setBuyerUserId("13800138000");
        request.setBuyerSkuCode("TELCO-100");
        request.setListingVersion(42L);
        request.setQuantity(1L);
        request.setTarget(target);
        request.setPaidAt(OffsetDateTime.parse("2026-08-11T10:30:00+08:00"));
        return request;
    }

    private static Set<String> collectFieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        collectFieldNames(node, names);
        return names;
    }

    private static void collectFieldNames(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.fieldNames().forEachRemaining(names::add);
        node.fields().forEachRemaining(entry -> collectFieldNames(entry.getValue(), names));
    }
}
