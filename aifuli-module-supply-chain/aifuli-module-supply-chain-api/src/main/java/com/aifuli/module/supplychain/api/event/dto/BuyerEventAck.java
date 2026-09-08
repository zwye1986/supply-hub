package com.aifuli.module.supplychain.api.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "采购商商城事件接收确认；商城必须先持久化 Inbox 和投影再返回成功；载荷冲突使用 CommonResult 错误")
@Data
public class BuyerEventAck implements Serializable {

    @Schema(description = "被确认的事件号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "EVT_01K1XXXXXXXXXXXXXXXXXXXXX")
    private String eventId;

    @Schema(description = "接收结果", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ACCEPTED")
    private BuyerEventAckStatus status;

    @Schema(description = "商城已经应用或保留的详情版本", example = "3")
    private Long detailVersion;

    @Schema(description = "商城完成持久化处理的时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:31:01Z")
    private OffsetDateTime processedAt;
}
