package com.aifuli.module.supplychain.api.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Schema(description = "商品目录变化唤醒事件；商城必须按游标拉取中台真相快照")
@Data
public final class CatalogChangedEventData implements BuyerEventData {

    @Schema(description = "中台已知最新商品变更游标", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "128")
    private Long latestCursor;

    @Schema(description = "触发通知的变更类型", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "UPSERT")
    private String changeType;

    @Schema(description = "变更发生时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:30:00Z")
    private OffsetDateTime occurredAt;
}
