package com.aifuli.module.supplychain.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "供应链总订单公共状态")
public enum SupplyChainOrderStatus {
    CREATED,
    PROCESSING,
    PARTIALLY_SUCCEEDED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    MANUAL
}
