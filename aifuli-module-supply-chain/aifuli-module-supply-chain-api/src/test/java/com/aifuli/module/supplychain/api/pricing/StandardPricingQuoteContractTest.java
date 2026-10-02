package com.aifuli.module.supplychain.api.pricing;

import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteRespDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标准商品报价RPC的公开契约。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#7-项目报价凭证与标准履约}。
 */
class StandardPricingQuoteContractTest {
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void rejectsMissingBindingFieldsAndNonPositiveQuantity() {
        StandardPricingQuoteReqDTO request = new StandardPricingQuoteReqDTO();
        request.setQuantity(0L);

        Set<String> properties = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(properties).contains("sourceSystem", "externalTenantId", "projectCode",
                "platformSkuCode", "buyerOrderNo", "quantity");
    }

    /**
     * 报价契约不再携带用户身份：永久键为「项目+项目订单号」，设计见
     * {@code docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#1-内部契约与归属}。
     */
    @Test
    void requestNoLongerCarriesBuyerUserId() {
        assertThat(java.util.Arrays.stream(StandardPricingQuoteReqDTO.class.getDeclaredFields())
                .map(field -> field.getName()))
                .doesNotContain("buyerUserId");
    }

    @Test
    void responseFreezesAllSettlementAndDisplayAmounts() {
        assertThat(java.util.Arrays.stream(StandardPricingQuoteRespDTO.class.getDeclaredFields())
                .map(field -> field.getName()))
                .contains("quoteToken", "pricingVersionId", "distributionUnitPriceCents",
                        "salesUnitPriceCents", "productSubtotalCents", "serviceFeeCents",
                        "userPayableAmountCents", "taxIncluded", "taxRateCode", "taxRateLabel",
                        "serviceFeeCopy", "expiresAt");
    }
}
