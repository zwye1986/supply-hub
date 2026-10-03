package com.aifuli.module.supplychain.api.pricing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 标准商品项目报价请求。报价永久键为「项目 + 采购商订单号」，不绑定用户、支付时间或调用方商品版本。
 * 设计见 docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#1-内部契约与归属。
 */
@Data
@Schema(description = "标准商品项目报价请求")
public class StandardPricingQuoteReqDTO implements Serializable {
    /** 调用来源系统编码 */
    @Schema(description = "调用来源系统编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 64)
    private String sourceSystem;

    /** 来源系统中的外部租户标识 */
    @Schema(description = "来源系统中的外部租户标识", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String externalTenantId;

    /** 采购项目全局永久编码 */
    @Schema(description = "采购项目全局永久编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 32)
    private String projectCode;

    /** 平台SKU永久业务编码 */
    @Schema(description = "平台SKU永久业务编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 64)
    private String platformSkuCode;

    /** 采购商侧C端订单号，CARD场景必填 */
    @Schema(description = "采购商侧C端订单号，CARD场景必填", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String buyerOrderNo;

    /** 报价商品数量，本期固定为1 */
    @Schema(description = "报价商品数量，本期固定为1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long quantity;
}
