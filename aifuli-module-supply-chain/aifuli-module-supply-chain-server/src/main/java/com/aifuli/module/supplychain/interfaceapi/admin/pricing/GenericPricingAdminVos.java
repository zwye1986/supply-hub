package com.aifuli.module.supplychain.interfaceapi.admin.pricing;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 分销商与项目通用批量定价共用的请求片段。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口}。
 */
public final class GenericPricingAdminVos {

    private GenericPricingAdminVos() {
    }

    @Data
    @Schema(description = "通用价格公式：(价格基数×百分比) 运算符 固定值")
    public static class FormulaRuleReqVO {
        @NotBlank
        @Schema(description = "DIRECT_MARKET_PRICE直接等于市场价，CUSTOM自定义")
        private String mode;
        @Schema(description = "MARKET_PRICE、SUGGESTED_SALE_PRICE、SUPPLY_PRICE；CUSTOM必填")
        private String base;
        @Schema(description = "大于0且最多2位小数，不设上限")
        private BigDecimal percentage;
        @Schema(description = "ADD、SUBTRACT、MULTIPLY、DIVIDE；CUSTOM必填")
        private String operator;
        @Schema(description = "加减时单位元且最多2位；乘除时为系数且最多4位；不得为负")
        private BigDecimal operand;
    }

    @Data
    @Schema(description = "服务费公式：(商品小计×百分比) 运算符 固定值")
    public static class ServiceFeeRuleReqVO {
        @Schema(description = "大于0且最多2位小数，不设上限")
        private BigDecimal percentage;
        @NotBlank
        @Schema(description = "ADD、SUBTRACT、MULTIPLY、DIVIDE")
        private String operator;
        @Schema(description = "加减时单位元且最多2位；乘除时为系数且最多4位；不得为负")
        private BigDecimal operand;
        @Schema(description = "是否隐藏自定义服务费文案；不影响费用展示和收取")
        private Boolean hideCopy;
        @Size(max = 256)
        @Schema(description = "服务费自定义文案；hideCopy=false时必填")
        private String copy;
    }

    @Data
    @Schema(description = "分销区和销售区利润隐藏阈值")
    public static class HideRuleReqVO {
        @Schema(description = "分销区利润隐藏阈值百分数，0到100，最多2位小数")
        private BigDecimal distributionThresholdPercent;
        @Schema(description = "销售区利润隐藏阈值百分数，0到100，最多2位小数")
        private BigDecimal salesThresholdPercent;
    }

    @Data
    @Schema(description = "税务展示属性；仅用于结算展示，不参与价格计算")
    public static class TaxMetadataReqVO {
        @Schema(description = "是否含税；与 taxRateCode 组成税务组，任一缺失则该组整体降级为继承")
        private Boolean taxIncluded;
        @Schema(description = "system动态字典supply_chain_tax_rate的启用值；与 taxIncluded 组成税务组，任一缺失则该组整体降级为继承")
        private String taxRateCode;
    }
}
