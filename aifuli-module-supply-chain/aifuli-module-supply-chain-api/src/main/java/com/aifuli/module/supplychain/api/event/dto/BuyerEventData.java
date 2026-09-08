package com.aifuli.module.supplychain.api.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

@Schema(description = "采购商事件封闭载荷",
        oneOf = {OrderSummaryChangedEventData.class, FulfillmentResultEventData.class,
                CatalogChangedEventData.class})
public sealed interface BuyerEventData extends Serializable
        permits OrderSummaryChangedEventData, FulfillmentResultEventData, CatalogChangedEventData {
}
