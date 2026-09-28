package com.aifuli.module.supplychain.api.pricing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "标准商品项目报价请求")
public class StandardPricingQuoteReqDTO implements Serializable {
    @Schema(description = "调用来源系统编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 64) private String sourceSystem;
    @Schema(description = "来源系统外部租户标识", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 128) private String externalTenantId;
    @Schema(description = "采购项目全局永久编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 32) private String projectCode;
    @Schema(description = "平台SKU永久业务编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 64) private String platformSkuCode;
    @Schema(description = "项目范围内用户标识；一期为规范化后的大陆手机号",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 128) private String buyerUserId;
    @Schema(description = "采购商订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank @Size(max = 128) private String buyerOrderNo;
    @Schema(description = "报价商品数量", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull @Positive private Long quantity;
}
