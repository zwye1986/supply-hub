package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "标准履约状态")
public enum FulfillmentStatus {
    ACCEPTED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    MANUAL
}
