package com.aifuli.module.supplychain.api.pricing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 项目订单首次永久冻结的标准商品报价响应。永久键为「项目 + 采购商订单号」，
 * 同键重复询价始终重放同一冻结结果；不再返回用户身份。
 * 设计见 docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#1-内部契约与归属。
 */
@Data
@Schema(description = "项目订单首次永久冻结的标准商品报价")
public class StandardPricingQuoteRespDTO implements Serializable {
    @Schema(description = "随机内部追踪标识；不作为HTTP请求字段，报价永久键为项目+采购商订单号",
            requiredMode = Schema.RequiredMode.REQUIRED)
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
    @Schema(description = "历史报价失效时间，Asia/Shanghai；永久冻结报价为空且不作为本流程下单条件")
    private OffsetDateTime expiresAt;
}
