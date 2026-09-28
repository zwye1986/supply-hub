package com.aifuli.module.supplychain.api.pricing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Data
@Schema(description = "冻结15分钟的标准商品项目报价")
public class StandardPricingQuoteRespDTO implements Serializable {
    @Schema(description = "随机不可猜测报价凭证", requiredMode = Schema.RequiredMode.REQUIRED)
    private String quoteToken;
    @Schema(description = "冻结的项目SKU综合定价版本主键", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long pricingVersionId;
    @Schema(description = "平台SKU永久业务编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String platformSkuCode;
    @Schema(description = "报价商品数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long quantity;
    @Schema(description = "冻结的商品分销单价，单位CNY分", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long distributionUnitPriceCents;
    @Schema(description = "冻结的商品销售单价，单位CNY分", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long salesUnitPriceCents;
    @Schema(description = "冻结的商品小计，单位CNY分", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long productSubtotalCents;
    @Schema(description = "冻结的平台服务费，单位CNY分", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long serviceFeeCents;
    @Schema(description = "冻结的用户应付总额，单位CNY分", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userPayableAmountCents;
    @Schema(description = "是否含税；仅用于结算展示", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean taxIncluded;
    @Schema(description = "system动态字典supply_chain_tax_rate的启用值", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taxRateCode;
    @Schema(description = "冻结的税率展示标签", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taxRateLabel;
    @Schema(description = "冻结的服务费展示文案；配置隐藏时为空")
    private String serviceFeeCopy;
    @Schema(description = "币种，一期固定CNY", requiredMode = Schema.RequiredMode.REQUIRED)
    private String currency;
    @Schema(description = "凭证失效时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private OffsetDateTime expiresAt;
}
