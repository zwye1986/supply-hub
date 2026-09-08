package com.aifuli.module.supplychain.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "供应链总订单售后状态")
public enum SupplyChainAftersaleStatus {
    NONE,
    PROCESSING,
    PARTIALLY_REFUNDED,
    FULLY_REFUNDED,
    MANUAL
}
