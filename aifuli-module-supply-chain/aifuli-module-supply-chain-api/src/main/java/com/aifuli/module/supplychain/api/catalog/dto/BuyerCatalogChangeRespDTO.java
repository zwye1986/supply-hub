package com.aifuli.module.supplychain.api.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "采购商商品增量变更；游标是中台变更表主键，不使用时间戳推断顺序")
@Data
public class BuyerCatalogChangeRespDTO implements Serializable {

    /** 采购商商品变更游标，同一采购商范围内单调递增但允许有间隙 */
    @Schema(description = "采购商商品变更游标，同一采购商范围内单调递增但允许有间隙", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "128")
    private Long cursor;

    /** 变更类型：UPSERT新增或更新，TOMBSTONE下架墓碑，AVAILABILITY_CHANGED可用性变化 */
    @Schema(description = "变更类型：UPSERT新增或更新，TOMBSTONE下架墓碑，AVAILABILITY_CHANGED可用性变化",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "UPSERT")
    private String changeType;

    /** 采购商范围内的商品编码 */
    @Schema(description = "采购商范围内的商品编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TELCO-100")
    private String buyerSkuCode;

    /** 商品快照；TOMBSTONE 可仅返回稳定商品标识 */
    @Schema(description = "商品快照；TOMBSTONE 可仅返回稳定商品标识")
    private BuyerCatalogItemRespDTO item;

    /** 变更发生时间，Asia/Shanghai */
    @Schema(description = "变更发生时间，Asia/Shanghai", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:30:00Z")
    private OffsetDateTime occurredAt;
}
