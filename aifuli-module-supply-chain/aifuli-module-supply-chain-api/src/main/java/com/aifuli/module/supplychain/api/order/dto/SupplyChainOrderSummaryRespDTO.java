package com.aifuli.module.supplychain.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "供应链总订单摘要；字段只来自唯一订单聚合总表")
@Data
public class SupplyChainOrderSummaryRespDTO implements Serializable {

    @Schema(description = "供应链中台订单号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SC_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String orderNo;

    @Schema(description = "订单类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "CARD")
    private String orderType;

    @Schema(description = "采购商业务编码快照", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "WUSHENG")
    private String buyerCode;

    @Schema(description = "采购商名称快照", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "吾盛")
    private String buyerName;

    @Schema(description = "采购商订单号；O2O 场景可为空", example = "C202608110001")
    private String buyerOrderNo;

    @Schema(description = "列表展示供应商编码快照；尚未完成首次路由时可为空", example = "QNM")
    private String supplierCode;

    @Schema(description = "列表展示供应商名称快照；尚未完成首次路由时可为空", example = "奇那梦")
    private String supplierName;

    @Schema(description = "采购商结算金额，单位为 CNY 分", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "9800")
    private Long buyerSettlementAmount;

    @Schema(description = "冻结的商品销售单价，单位CNY分")
    private Long salesUnitPriceCents;

    @Schema(description = "冻结的商品小计，单位CNY分")
    private Long productSubtotalCents;

    @Schema(description = "冻结的平台服务费，单位CNY分")
    private Long serviceFeeCents;

    @Schema(description = "冻结的用户应付总额，单位CNY分")
    private Long userPayableAmountCents;

    @Schema(description = "币种，一期固定 CNY", requiredMode = Schema.RequiredMode.REQUIRED, example = "CNY")
    private String currency;

    @Schema(description = "标准 SKU 编码快照；O2O 场景可为空", example = "TELCO_100")
    private String skuCode;

    @Schema(description = "标准 SKU 名称快照；O2O 场景可为空", example = "话费直充 100 元")
    private String skuName;

    @Schema(description = "CARD 场景请求数量", example = "1")
    private Long quantity;

    @Schema(description = "公共主状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "PROCESSING")
    private SupplyChainOrderStatus orderStatus;

    @Schema(description = "售后状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "NONE")
    private SupplyChainAftersaleStatus aftersaleStatus;

    @Schema(description = "只读业务阶段稳定编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SUPPLIER_PROCESSING")
    private String businessStage;

    @Schema(description = "场景详情类型", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FULFILLMENT")
    private String detailType;

    @Schema(description = "场景详情已投影版本", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "3")
    private Long detailVersion;

    @Schema(description = "订单受理时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:30:01Z")
    private OffsetDateTime createdAt;

    @Schema(description = "订单最后更新时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:31:00Z")
    private OffsetDateTime updatedAt;

    @Schema(description = "订单进入业务终态时间", example = "2026-08-11T02:31:00Z")
    private OffsetDateTime completedAt;
}
