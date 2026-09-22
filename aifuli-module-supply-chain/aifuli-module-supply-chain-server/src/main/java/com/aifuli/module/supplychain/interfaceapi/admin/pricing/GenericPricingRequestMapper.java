package com.aifuli.module.supplychain.interfaceapi.admin.pricing;

import com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.BuyerProductBatchPricingReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.BuyerProductCreatePricingReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.ProjectAdminController.ProjectProductBatchPricingReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.PlatformSkuAdminController.PlatformSkuBatchPricingReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.FormulaRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.HideRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.ServiceFeeRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.TaxMetadataReqVO;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FixedOperator;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceBase;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxGroupValidator;

/**
 * 管理端通用定价请求到领域对象的转换，并在边界完成税务原子组校验。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口}。
 */
public final class GenericPricingRequestMapper {

    private GenericPricingRequestMapper() {
    }

    public static PricingConfig toPlatformConfig(PlatformSkuBatchPricingReqVO request) {
        PricingTaxGroupValidator.requireComplete(request.getTaxIncluded(), request.getTaxRateCode());
        return new PricingConfig(toFormula(request.getDistributionRule(), true),
                toFormula(request.getSalesRule(), true),
                toServiceFee(request.getServiceFeeRule(), true),
                new HideRule(request.getDistributionHideThresholdPercent(), request.getSalesHideThresholdPercent()),
                new TaxMetadata(request.getTaxIncluded(), request.getTaxRateCode(), null));
    }

    public static ProjectOverrides toBuyerOverrides(BuyerProductBatchPricingReqVO request) {
        return toBuyerOverrides(request.isDistributionInherited(), request.getDistributionRule(),
                request.isSalesInherited(), request.getSalesRule(),
                request.isServiceFeeInherited(), request.getServiceFeeRule(),
                request.isHideInherited(), request.getDistributionHideThresholdPercent(),
                request.getSalesHideThresholdPercent(), request.isTaxInherited(),
                request.getTaxIncluded(), request.getTaxRateCode());
    }

    public static ProjectOverrides toBuyerOverrides(BuyerProductCreatePricingReqVO request) {
        return toBuyerOverrides(request.isDistributionInherited(), request.getDistributionRule(),
                request.isSalesInherited(), request.getSalesRule(),
                request.isServiceFeeInherited(), request.getServiceFeeRule(),
                request.isHideInherited(), request.getDistributionHideThresholdPercent(),
                request.getSalesHideThresholdPercent(), request.isTaxInherited(),
                request.getTaxIncluded(), request.getTaxRateCode());
    }

    private static ProjectOverrides toBuyerOverrides(boolean distributionInherited, FormulaRuleReqVO distributionRule,
                                                     boolean salesInherited, FormulaRuleReqVO salesRule,
                                                     boolean serviceFeeInherited, ServiceFeeRuleReqVO serviceFeeRule,
                                                     boolean hideInherited, java.math.BigDecimal distributionThreshold,
                                                     java.math.BigDecimal salesThreshold, boolean taxInherited,
                                                     Boolean taxIncluded, String taxRateCode) {
        PricingTaxGroupValidator.requireProjectGroup(taxInherited,
                taxIncluded != null || taxRateCode != null, taxIncluded, taxRateCode);
        return new ProjectOverrides(distributionInherited, toFormula(distributionRule, false),
                salesInherited, toFormula(salesRule, false),
                serviceFeeInherited, toServiceFee(serviceFeeRule, false),
                hideInherited, hideInherited ? null : new HideRule(distributionThreshold, salesThreshold),
                taxInherited, taxInherited ? null : new TaxMetadata(taxIncluded, taxRateCode, null));
    }

    public static ProjectOverrides toProjectOverrides(ProjectProductBatchPricingReqVO request) {
        TaxMetadataReqVO taxMetadata = request.getTaxMetadata();
        PricingTaxGroupValidator.requireProjectGroup(request.isTaxInherited(), taxMetadata != null,
                taxMetadata == null ? null : taxMetadata.getTaxIncluded(),
                taxMetadata == null ? null : taxMetadata.getTaxRateCode());
        return new ProjectOverrides(request.isDistributionInherited(), toFormula(request.getDistributionRule(), false),
                request.isSalesInherited(), toFormula(request.getSalesRule(), false),
                request.isServiceFeeInherited(), toServiceFee(request.getServiceFeeRule(), false),
                request.isHideInherited(), toHide(request.getHideRule()),
                request.isTaxInherited(), taxMetadata == null ? null
                : new TaxMetadata(taxMetadata.getTaxIncluded(), taxMetadata.getTaxRateCode(), null));
    }

    private static FormulaRule toFormula(FormulaRuleReqVO request, boolean required) {
        if (request == null) {
            if (required) {
                throw new IllegalArgumentException("price formula is required");
            }
            return null;
        }
        PriceMode mode = PriceMode.valueOf(request.getMode());
        return new FormulaRule(mode,
                request.getBase() == null ? null : PriceBase.valueOf(request.getBase()),
                request.getPercentage(),
                request.getOperator() == null ? null : FixedOperator.valueOf(request.getOperator()),
                request.getOperand());
    }

    private static ServiceFeeRule toServiceFee(ServiceFeeRuleReqVO request, boolean required) {
        if (request == null) {
            if (required) {
                throw new IllegalArgumentException("service fee rule is required");
            }
            return null;
        }
        return new ServiceFeeRule(request.getPercentage(), FixedOperator.valueOf(request.getOperator()),
                request.getOperand(), Boolean.TRUE.equals(request.getHideCopy()), request.getCopy());
    }

    private static HideRule toHide(HideRuleReqVO request) {
        if (request == null) {
            return null;
        }
        return new HideRule(request.getDistributionThresholdPercent(), request.getSalesThresholdPercent());
    }
}
