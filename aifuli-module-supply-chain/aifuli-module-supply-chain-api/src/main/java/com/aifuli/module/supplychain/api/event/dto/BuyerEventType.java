package com.aifuli.module.supplychain.api.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "供应链中台投递给采购商商城的事件类型")
public enum BuyerEventType {
    ORDER_SUMMARY_CHANGED,
    FULFILLMENT_RESULT,
    CATALOG_CHANGED
}
