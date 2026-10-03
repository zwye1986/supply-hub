package com.aifuli.module.supplychain.api.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Schema(description = "商品目录变化唤醒事件；商城必须按游标拉取中台真相快照")
@Data
public final class CatalogChangedEventData implements BuyerEventData {

    /** 采购商商品变更游标，同一采购商范围内单调递增但允许有间隙 */
    @Schema(description = "采购商商品变更游标，同一采购商范围内单调递增但允许有间隙", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "128")
    private Long latestCursor;

    /** 变更类型：UPSERT新增或更新，TOMBSTONE下架墓碑，AVAILABILITY_CHANGED可用性变化 */
    @Schema(description = "变更类型：UPSERT新增或更新，TOMBSTONE下架墓碑，AVAILABILITY_CHANGED可用性变化", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "UPSERT")
    private String changeType;

    /** 变更发生时间，Asia/Shanghai */
    @Schema(description = "变更发生时间，Asia/Shanghai", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:30:00Z")
    private OffsetDateTime occurredAt;
}
