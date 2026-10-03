package com.aifuli.module.supplychain.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "采购商供应链订单详情查询请求")
@Data
public class SupplyChainOrderDetailQueryReqDTO implements Serializable {

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

    /** 供应链总订单稳定业务编号 */
    @Schema(description = "供应链总订单稳定业务编号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SC_01K1XXXXXXXXXXXXXXXXXXXXXX")
    @NotBlank(message = "供应链中台订单号不能为空")
    @Size(max = 32)
    private String orderNo;
}
