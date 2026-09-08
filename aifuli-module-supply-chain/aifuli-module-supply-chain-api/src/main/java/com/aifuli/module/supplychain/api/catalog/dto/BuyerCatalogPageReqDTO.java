package com.aifuli.module.supplychain.api.catalog.dto;

import com.aifuli.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "采购商标准商品池分页查询请求")
@Data
@EqualsAndHashCode(callSuper = true)
public class BuyerCatalogPageReqDTO extends PageParam {

    @Schema(description = "调用来源系统编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "AIFULI_MALL")
    @NotBlank(message = "调用来源系统不能为空")
    @Size(max = 64)
    private String sourceSystem;

    @Schema(description = "来源系统中的外部租户标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "tenant-wusheng")
    @NotBlank(message = "外部租户标识不能为空")
    @Size(max = 128)
    private String externalTenantId;

    @Schema(description = "采购商商品编码", example = "TELCO-100")
    @Size(max = 64)
    private String buyerSkuCode;

    @Schema(description = "履约类型", example = "DIRECT_RECHARGE")
    @Size(max = 32)
    private String fulfillmentType;

    @Schema(description = "仅返回当前可用商品", example = "true")
    private Boolean availableOnly;
}
