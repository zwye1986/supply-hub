package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "标准履约受理回执查询请求")
@Data
public class FulfillmentReceiptQueryReqDTO implements Serializable {

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

    /** 采购项目全局永久编码 */
    @Schema(description = "采购项目全局永久编码",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "PRJ_KEY_A")
    @NotBlank(message = "采购项目业务编码不能为空")
    @Size(max = 64)
    private String projectCode;

    /** 采购商侧C端订单号，与buyer_id组成永久受理键 */
    @Schema(description = "采购商侧C端订单号，与buyer_id组成永久受理键", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "C202608110001")
    @NotBlank(message = "采购商订单号不能为空")
    @Size(max = 128)
    private String buyerOrderNo;
}
