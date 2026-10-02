package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "标准履约结果查询请求")
@Data
public class FulfillmentResultQueryReqDTO implements Serializable {

    @Schema(description = "调用来源系统编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "AIFULI_MALL")
    @NotBlank(message = "调用来源系统不能为空")
    @Size(max = 64)
    private String sourceSystem;

    @Schema(description = "来源系统中的外部租户标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "tenant-wusheng")
    @NotBlank(message = "外部租户标识不能为空")
    @Size(max = 128)
    private String externalTenantId;

    @Schema(description = "采购项目业务编码；订单按项目永久键查询，与来源租户归属校验",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "PRJ_KEY_A")
    @NotBlank(message = "采购项目业务编码不能为空")
    @Size(max = 64)
    private String projectCode;

    @Schema(description = "采购商订单号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "C202608110001")
    @NotBlank(message = "采购商订单号不能为空")
    @Size(max = 128)
    private String buyerOrderNo;
}
