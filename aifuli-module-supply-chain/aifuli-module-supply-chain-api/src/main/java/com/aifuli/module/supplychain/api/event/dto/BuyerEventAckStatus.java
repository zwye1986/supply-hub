package com.aifuli.module.supplychain.api.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "商城事件接收结果")
public enum BuyerEventAckStatus {
    ACCEPTED,
    DUPLICATE_ACCEPTED,
    OUTDATED_ACCEPTED
}
