package com.aifuli.module.supplychain.api.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "采购商标准商品增量拉取请求")
@Data
public class BuyerCatalogChangesReqDTO implements Serializable {

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

    /** 采购商商品变更游标，同一采购商范围内单调递增但允许有间隙；首次拉取传 0 */
    @Schema(description = "采购商商品变更游标，同一采购商范围内单调递增但允许有间隙；首次拉取传 0", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
    @NotNull(message = "商品变更游标不能为空")
    @Min(value = 0, message = "商品变更游标不能小于 0")
    private Long cursor;

    /** 单次拉取上限 */
    @Schema(description = "单次拉取上限", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    @NotNull(message = "拉取上限不能为空")
    @Min(value = 1, message = "拉取上限最小为 1")
    @Max(value = 200, message = "拉取上限最大为 200")
    private Integer limit = 100;
}
