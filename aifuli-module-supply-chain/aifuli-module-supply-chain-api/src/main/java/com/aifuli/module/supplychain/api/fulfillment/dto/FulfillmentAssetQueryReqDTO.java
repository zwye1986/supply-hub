package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "履约敏感资产授权查询请求")
@Data
public class FulfillmentAssetQueryReqDTO implements Serializable {

    @Schema(description = "调用来源系统编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "AIFULI_MALL")
    @NotBlank(message = "调用来源系统不能为空")
    @Size(max = 64)
    private String sourceSystem;

    @Schema(description = "来源系统中的外部租户标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "tenant-wusheng")
    @NotBlank(message = "外部租户标识不能为空")
    @Size(max = 128)
    private String externalTenantId;

    @Schema(description = "履约请求号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FR_01K1XXXXXXXXXXXXXXXXXXXXXX")
    @NotBlank(message = "履约请求号不能为空")
    @Size(max = 32)
    private String fulfillmentRequestNo;

    @Schema(description = "资产所属采购商用户标识；必须与订单固化归属一致",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "member-1024")
    @NotBlank(message = "采购商用户标识不能为空")
    @Size(max = 128)
    private String buyerUserId;

    @Schema(description = "调用方声明的权限 Scope，必须为 FULFILLMENT_ASSET_READ",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "FULFILLMENT_ASSET_READ")
    @NotBlank(message = "资产查询 Scope 不能为空")
    @Size(max = 64)
    private String scopeCode;
}
