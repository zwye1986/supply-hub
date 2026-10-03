package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "履约敏感资产授权查询请求")
@Data
public class FulfillmentAssetQueryReqDTO implements Serializable {

    /** 调用来源系统编码 */
    @Schema(description = "调用来源系统编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "AIFULI_MALL")
    @NotBlank(message = "调用来源系统不能为空")
    @Size(max = 64)
    private String sourceSystem;

    /** 来源系统中的外部租户标识 */
    @Schema(description = "来源系统中的外部租户标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "tenant-wusheng")
    @NotBlank(message = "外部租户标识不能为空")
    @Size(max = 128)
    private String externalTenantId;

    /** 履约请求稳定业务编号 */
    @Schema(description = "履约请求稳定业务编号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FR_01K1XXXXXXXXXXXXXXXXXXXXXX")
    @NotBlank(message = "履约请求号不能为空")
    @Size(max = 32)
    private String fulfillmentRequestNo;

    /** 采购项目全局永久编码 */
    @Schema(description = "采购项目全局永久编码",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "PRJ_KEY_A")
    @NotBlank(message = "采购项目业务编码不能为空")
    @Size(max = 64)
    private String projectCode;

    /** 授权访问所使用的权限Scope或操作编码 */
    @Schema(description = "授权访问所使用的权限Scope或操作编码",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "FULFILLMENT_ASSET_READ")
    @NotBlank(message = "资产查询 Scope 不能为空")
    @Size(max = 64)
    private String scopeCode;
}
