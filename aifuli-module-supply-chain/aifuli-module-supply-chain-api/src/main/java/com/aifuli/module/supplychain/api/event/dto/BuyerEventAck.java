package com.aifuli.module.supplychain.api.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "采购商商城事件接收确认；商城必须先持久化 Inbox 和投影再返回成功；载荷冲突使用 CommonResult 错误")
@Data
public class BuyerEventAck implements Serializable {

    /**
     * 被消费的Outbox事件稳定业务编号
     */
    @Schema(description = "被消费的Outbox事件稳定业务编号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "EVT_01K1XXXXXXXXXXXXXXXXXXXXX")
    private String eventId;

    /**
     * 采购商商城事件接收结果：ACCEPTED-已接收
     */
    @Schema(description = "采购商商城事件接收结果：ACCEPTED-已接收", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ACCEPTED")
    private BuyerEventAckStatus status;

    /**
     * 采购商商城已经应用或保留的详情版本
     */
    @Schema(description = "采购商商城已经应用或保留的详情版本", example = "3")
    private Long detailVersion;

    /**
     * 商城完成持久化处理的时间，Asia/Shanghai
     */
    @Schema(description = "商城完成持久化处理的时间，Asia/Shanghai", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:31:01Z")
    private OffsetDateTime processedAt;
}
