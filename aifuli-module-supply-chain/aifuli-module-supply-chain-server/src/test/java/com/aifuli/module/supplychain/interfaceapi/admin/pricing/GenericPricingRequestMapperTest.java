package com.aifuli.module.supplychain.interfaceapi.admin.pricing;

import com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.BuyerProductBatchPricingReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.ProjectAdminController.ProjectProductBatchPricingReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.FormulaRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.ServiceFeeRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.TaxMetadataReqVO;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxRateCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 税务组必须整体覆盖或整体继承，缺省 boolean 不得被解释为不含税。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#1-范围与不变量}。
 */
class GenericPricingRequestMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsProjectTaxOverrideWhenTaxIncludedIsOmitted() throws Exception {
        ProjectProductBatchPricingReqVO request = objectMapper.readValue("""
                {
                  "listingCodes": ["PLT_1"],
                  "taxInherited": false,
                  "taxMetadata": { "taxRateCode": "NON_TAXABLE" }
                }
                """, ProjectProductBatchPricingReqVO.class);
        assertThat(request.getTaxMetadata().getTaxIncluded()).isNull();
        assertThatThrownBy(() -> GenericPricingRequestMapper.toProjectOverrides(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
    }

    @Test
    void rejectsInheritedTaxWhenOverrideObjectIsPresent() {
        ProjectProductBatchPricingReqVO request = new ProjectProductBatchPricingReqVO();
        request.setListingCodes(List.of("PLT_1"));
        request.setTaxInherited(true);
        request.setTaxMetadata(new TaxMetadataReqVO());
        assertThatThrownBy(() -> GenericPricingRequestMapper.toProjectOverrides(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry override fields");
    }

    @Test
    void rejectsBuyerTaxWhenTaxIncludedIsNull() {
        BuyerProductBatchPricingReqVO request = new BuyerProductBatchPricingReqVO();
        request.setBuyerProductCodes(List.of("BPD_1"));
        request.setDistributionRule(direct());
        request.setSalesRule(direct());
        request.setServiceFeeRule(fee());
        request.setTaxRateCode(PricingTaxRateCodes.NON_TAXABLE);
        assertThatThrownBy(() -> GenericPricingRequestMapper.toBuyerConfig(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
    }

    private static FormulaRuleReqVO direct() {
        FormulaRuleReqVO rule = new FormulaRuleReqVO();
        rule.setMode("DIRECT_MARKET_PRICE");
        return rule;
    }

    private static ServiceFeeRuleReqVO fee() {
        ServiceFeeRuleReqVO rule = new ServiceFeeRuleReqVO();
        rule.setPercentage(new BigDecimal("1"));
        rule.setOperator("MULTIPLY");
        rule.setOperand(BigDecimal.ZERO);
        rule.setHideCopy(true);
        return rule;
    }
}
