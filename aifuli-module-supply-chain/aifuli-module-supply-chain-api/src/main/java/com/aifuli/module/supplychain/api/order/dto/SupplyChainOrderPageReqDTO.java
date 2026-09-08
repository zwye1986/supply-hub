package com.aifuli.module.supplychain.api.order.dto;

import com.aifuli.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "采购商供应链订单分页查询请求")
@Data
@EqualsAndHashCode(callSuper = true)
public class SupplyChainOrderPageReqDTO extends PageParam {

    @Schema(description = "调用来源系统编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "AIFULI_MALL")
    @NotBlank(message = "调用来源系统不能为空")
    @Size(max = 64)
    private String sourceSystem;

    @Schema(description = "来源系统中的外部租户标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "tenant-wusheng")
    @NotBlank(message = "外部租户标识不能为空")
    @Size(max = 128)
    private String externalTenantId;

    @Schema(description = "供应链中台订单号", example = "SC_01K1XXXXXXXXXXXXXXXXXXXXXX")
    @Size(max = 32)
    private String orderNo;

    @Schema(description = "采购商订单号", example = "C202608110001")
    @Size(max = 128)
    private String buyerOrderNo;

    @Schema(description = "列表展示供应商编码", example = "QNM")
    @Size(max = 32)
    private String supplierCode;

    @Schema(description = "标准 SKU 编码", example = "TELCO_100")
    @Size(max = 32)
    private String skuCode;

    @Schema(description = "公共主状态", example = "PROCESSING")
    private SupplyChainOrderStatus orderStatus;
}
