package com.aifuli.module.supplychain.api.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "采购商商品增量变更；游标是中台变更表主键，不使用时间戳推断顺序")
@Data
public class BuyerCatalogChangeRespDTO implements Serializable {

    @Schema(description = "单调递增且允许有间隙的变更游标", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "128")
    private Long cursor;

    @Schema(description = "变更类型：UPSERT、TOMBSTONE 或 AVAILABILITY_CHANGED",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "UPSERT")
    private String changeType;

    @Schema(description = "采购商商品编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TELCO-100")
    private String buyerSkuCode;

    @Schema(description = "商品快照；TOMBSTONE 可仅返回稳定商品标识")
    private BuyerCatalogItemRespDTO item;

    @Schema(description = "变更发生时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:30:00Z")
    private OffsetDateTime occurredAt;
}
