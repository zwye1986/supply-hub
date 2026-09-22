package com.aifuli.module.supplychain.interfaceapi.admin.pricing;

import com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.BuyerProductBatchPricingReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.BuyerProductCreatePricingReqVO;
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

/**
 * 税务组兼容规则：只有「声明覆盖且 taxIncluded、taxRateCode 同时有效」保留覆盖；
 * 覆盖组残缺时降级为整体继承并丢弃字段，声明继承时忽略携带的税务字段。
 * 缺省 boolean 不得被解释为不含税。设计见
 * {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性}。
 */
class GenericPricingRequestMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void downgradesProjectTaxOverrideToInheritWhenTaxIncludedIsOmitted() throws Exception {
        ProjectProductBatchPricingReqVO request = objectMapper.readValue("""
                {
                  "listingCodes": ["PLT_1"],
                  "taxInherited": false,
                  "taxMetadata": { "taxRateCode": "NON_TAXABLE" }
                }
                """, ProjectProductBatchPricingReqVO.class);
        assertThat(request.getTaxMetadata().getTaxIncluded()).isNull();
        var overrides = GenericPricingRequestMapper.toProjectOverrides(request);
        assertThat(overrides.taxInherited()).isTrue();
        assertThat(overrides.taxMetadata()).isNull();
    }

    @Test
    void ignoresEmptyTaxMetadataWhenInherited() {
        ProjectProductBatchPricingReqVO request = new ProjectProductBatchPricingReqVO();
        request.setListingCodes(List.of("PLT_1"));
        request.setTaxInherited(true);
        request.setTaxMetadata(new TaxMetadataReqVO());
        var overrides = GenericPricingRequestMapper.toProjectOverrides(request);
        assertThat(overrides.taxInherited()).isTrue();
        assertThat(overrides.taxMetadata()).isNull();
    }

    @Test
    void ignoresCompleteTaxMetadataWhenInherited() {
        ProjectProductBatchPricingReqVO request = new ProjectProductBatchPricingReqVO();
        request.setListingCodes(List.of("PLT_1"));
        request.setTaxInherited(true);
        TaxMetadataReqVO taxMetadata = new TaxMetadataReqVO();
        taxMetadata.setTaxIncluded(true);
        taxMetadata.setTaxRateCode("RATE_6");
        request.setTaxMetadata(taxMetadata);
        var overrides = GenericPricingRequestMapper.toProjectOverrides(request);
        assertThat(overrides.taxInherited()).isTrue();
        assertThat(overrides.taxMetadata()).isNull();
    }

    @Test
    void mapsCreatePricingWithCompleteTaxOverride() {
        BuyerProductCreatePricingReqVO request = new BuyerProductCreatePricingReqVO();
        request.setDistributionInherited(false);
        request.setDistributionRule(direct());
        request.setSalesInherited(true);
        request.setServiceFeeInherited(true);
        request.setHideInherited(true);
        request.setTaxInherited(false);
        request.setTaxIncluded(false);
        request.setTaxRateCode(PricingTaxRateCodes.NON_TAXABLE);

        var overrides = GenericPricingRequestMapper.toBuyerOverrides(request);
        assertThat(overrides.distributionInherited()).isFalse();
        assertThat(overrides.salesInherited()).isTrue();
        assertThat(overrides.taxInherited()).isFalse();
        assertThat(overrides.taxMetadata().taxIncluded()).isFalse();
        assertThat(overrides.taxMetadata().taxRateCode()).isEqualTo(PricingTaxRateCodes.NON_TAXABLE);
    }

    @Test
    void downgradesCreatePricingToInheritWhenTaxRateCodeMissing() {
        // 复现管理端只传 taxInherited=false + taxIncluded=false、漏传 taxRateCode 的请求
        BuyerProductCreatePricingReqVO request = new BuyerProductCreatePricingReqVO();
        request.setDistributionInherited(false);
        request.setDistributionRule(direct());
        request.setSalesInherited(true);
        request.setServiceFeeInherited(true);
        request.setHideInherited(false);
        request.setDistributionHideThresholdPercent(new BigDecimal("1"));
        request.setTaxInherited(false);
        request.setTaxIncluded(false);

        var overrides = GenericPricingRequestMapper.toBuyerOverrides(request);
        assertThat(overrides.taxInherited()).isTrue();
        assertThat(overrides.taxMetadata()).isNull();
        assertThat(overrides.distributionInherited()).isFalse();
    }

    @Test
    void downgradesBuyerBatchTaxToInheritWhenTaxIncludedIsNull() {
        BuyerProductBatchPricingReqVO request = new BuyerProductBatchPricingReqVO();
        request.setBuyerProductCodes(List.of("BPD_1"));
        request.setDistributionRule(direct());
        request.setSalesRule(direct());
        request.setServiceFeeRule(fee());
        request.setTaxRateCode(PricingTaxRateCodes.NON_TAXABLE);

        var overrides = GenericPricingRequestMapper.toBuyerOverrides(request);
        assertThat(overrides.taxInherited()).isTrue();
        assertThat(overrides.taxMetadata()).isNull();
    }

    @Test
    void keepsBuyerBatchTaxOverrideWhenComplete() {
        BuyerProductBatchPricingReqVO request = new BuyerProductBatchPricingReqVO();
        request.setBuyerProductCodes(List.of("BPD_1"));
        request.setDistributionRule(direct());
        request.setSalesRule(direct());
        request.setServiceFeeRule(fee());
        request.setTaxInherited(false);
        request.setTaxIncluded(true);
        request.setTaxRateCode("RATE_6");

        var overrides = GenericPricingRequestMapper.toBuyerOverrides(request);
        assertThat(overrides.taxInherited()).isFalse();
        assertThat(overrides.taxMetadata().taxIncluded()).isTrue();
        assertThat(overrides.taxMetadata().taxRateCode()).isEqualTo("RATE_6");
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
