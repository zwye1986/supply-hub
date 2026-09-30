package com.aifuli.module.supplychain.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "场景详情可用性；不可用不改变总订单查询的成功语义")
public enum DetailAvailability {
    AVAILABLE,
    UNAVAILABLE
}
