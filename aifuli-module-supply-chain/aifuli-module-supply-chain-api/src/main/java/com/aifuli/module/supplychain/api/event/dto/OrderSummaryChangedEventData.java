package com.aifuli.module.supplychain.api.event.dto;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainAftersaleStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Schema(description = "订单摘要变化事件载荷")
@Data
public final class OrderSummaryChangedEventData implements BuyerEventData {

    @Schema(description = "供应链中台订单号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SC_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String orderNo;

    @Schema(description = "采购商订单号", example = "C202608110001")
    private String buyerOrderNo;

    @Schema(description = "订单类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "CARD")
    private String orderType;

    @Schema(description = "公共主状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "PROCESSING")
    private SupplyChainOrderStatus orderStatus;

    @Schema(description = "售后状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "NONE")
    private SupplyChainAftersaleStatus aftersaleStatus;

    @Schema(description = "只读业务阶段稳定编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SUPPLIER_PROCESSING")
    private String businessStage;

    @Schema(description = "场景详情版本；商城据此拒绝乱序回退", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "3")
    private Long detailVersion;

    @Schema(description = "列表展示供应商编码快照", example = "QNM")
    private String supplierCode;

    @Schema(description = "列表展示供应商名称快照", example = "奇那梦")
    private String supplierName;

    @Schema(description = "总订单最后更新时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:31:00Z")
    private OffsetDateTime updatedAt;
}
