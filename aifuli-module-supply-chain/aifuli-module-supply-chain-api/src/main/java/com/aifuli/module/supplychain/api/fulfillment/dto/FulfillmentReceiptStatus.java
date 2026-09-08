package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "受理回执状态：处理中、已受理、可重试拒绝或最终拒绝")
public enum FulfillmentReceiptStatus {
    PROCESSING,
    ACCEPTED,
    REJECTED_RETRYABLE,
    REJECTED_FINAL
}
