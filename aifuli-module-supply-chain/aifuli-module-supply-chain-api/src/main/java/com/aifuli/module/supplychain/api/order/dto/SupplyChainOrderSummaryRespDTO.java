package com.aifuli.module.supplychain.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "供应链总订单摘要；字段只来自唯一订单聚合总表")
@Data
public class SupplyChainOrderSummaryRespDTO implements Serializable {

    /** 供应链总订单稳定业务编号 */
    @Schema(description = "供应链总订单稳定业务编号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "202610131530274200000001")
    private String orderNo;

    /** 订单类型：CARD标准商品履约，O2O配送O2O渠道，INSTORE到店购渠道 */
    @Schema(description = "订单类型：CARD标准商品履约，O2O配送O2O渠道，INSTORE到店购渠道", requiredMode = Schema.RequiredMode.REQUIRED, example = "CARD")
    private String orderType;

    /** 受理时采购商业务编码快照 */
    @Schema(description = "受理时采购商业务编码快照", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "WUSHENG")
    private String buyerCode;

    /** 受理时采购商名称快照 */
    @Schema(description = "受理时采购商名称快照", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "吾盛")
    private String buyerName;

    /** 采购商侧C端订单号，CARD场景必填 */
    @Schema(description = "采购商侧C端订单号，CARD场景必填", example = "C202608110001")
    private String buyerOrderNo;

    /** 列表展示供应商编码快照 */
    @Schema(description = "列表展示供应商编码快照；尚未完成首次路由时可为空", example = "QNM")
    private String supplierCode;

    /** 列表展示供应商名称快照 */
    @Schema(description = "列表展示供应商名称快照；尚未完成首次路由时可为空", example = "奇那梦")
    private String supplierName;

    /** 采购商应向中台结算的订单金额，单位为分 */
    @Schema(description = "采购商应向中台结算的订单金额，单位为分", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "9800")
    private Long buyerSettlementAmount;

    /** 冻结的商品销售单价，单位CNY分 */
    @Schema(description = "冻结的商品销售单价，单位CNY分")
    private Long salesUnitPriceCents;

    /** 冻结的商品小计，单位CNY分 */
    @Schema(description = "冻结的商品小计，单位CNY分")
    private Long productSubtotalCents;

    /** 冻结的平台服务费，单位CNY分 */
    @Schema(description = "冻结的平台服务费，单位CNY分")
    private Long serviceFeeCents;

    /** 冻结的用户应付总额，单位CNY分 */
    @Schema(description = "冻结的用户应付总额，单位CNY分")
    private Long userPayableAmountCents;

    /** 订单币种，一期固定CNY */
    @Schema(description = "订单币种，一期固定CNY", requiredMode = Schema.RequiredMode.REQUIRED, example = "CNY")
    private String currency;

    /** CARD场景平台SKU编码快照 */
    @Schema(description = "CARD场景平台SKU编码快照；O2O 场景可为空", example = "TELCO_100")
    private String skuCode;

    /** CARD场景平台SKU名称快照 */
    @Schema(description = "CARD场景平台SKU名称快照；O2O 场景可为空", example = "话费直充 100 元")
    private String skuName;

    /** CARD场景请求数量 */
    @Schema(description = "CARD场景请求数量", example = "1")
    private Long quantity;

    /** 公共主状态：0已创建，10处理中，20部分成功，30成功，40失败，50已取消，90人工处理 */
    @Schema(description = "公共主状态：0已创建，10处理中，20部分成功，30成功，40失败，50已取消，90人工处理", requiredMode = Schema.RequiredMode.REQUIRED, example = "PROCESSING")
    private SupplyChainOrderStatus orderStatus;

    /** 履约失败稳定原因码；历史缺失为空，不改变受理状态 */
    @Schema(description = "履约失败稳定原因码；可选")
    private String failureReasonCode;

    /** 履约失败脱敏说明；不包含供应商价格、账号、凭据或内部Case */
    @Schema(description = "履约失败脱敏说明；可选")
    private String failureReasonNote;

    /** 售后状态：0无售后，10处理中，20部分退款，30全部退款，90人工处理 */
    @Schema(description = "售后状态：0无售后，10处理中，20部分退款，30全部退款，90人工处理", requiredMode = Schema.RequiredMode.REQUIRED, example = "NONE")
    private SupplyChainAftersaleStatus aftersaleStatus;

    /** 当前业务阶段稳定编码 */
    @Schema(description = "当前业务阶段稳定编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SUPPLIER_PROCESSING")
    private String businessStage;

    /** 场景详情类型：FULFILLMENT、O2O_CHANNEL 或 INSTORE_CHANNEL */
    @Schema(description = "场景详情类型：FULFILLMENT、O2O_CHANNEL 或 INSTORE_CHANNEL", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FULFILLMENT")
    private String detailType;

    /** 已投影到总表的场景详情版本号 */
    @Schema(description = "已投影到总表的场景详情版本号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "3")
    private Long detailVersion;

    /** 订单受理时间，Asia/Shanghai */
    @Schema(description = "订单受理时间，Asia/Shanghai", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:30:01Z")
    private OffsetDateTime createdAt;

    /** 订单最后更新时间，Asia/Shanghai */
    @Schema(description = "订单最后更新时间，Asia/Shanghai", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:31:00Z")
    private OffsetDateTime updatedAt;

    /** 订单进入业务终态时间，Asia/Shanghai */
    @Schema(description = "订单进入业务终态时间，Asia/Shanghai", example = "2026-08-11T02:31:00Z")
    private OffsetDateTime completedAt;
}
