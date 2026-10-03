package com.aifuli.module.supplychain.api.event.dto;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainAftersaleStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Schema(description = "订单摘要变化事件载荷")
@Data
public final class OrderSummaryChangedEventData implements BuyerEventData {

    /** 供应链总订单稳定业务编号 */
    @Schema(description = "供应链总订单稳定业务编号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SC_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String orderNo;

    /** 采购商侧C端订单号，CARD场景必填 */
    @Schema(description = "采购商侧C端订单号，CARD场景必填", example = "C202608110001")
    private String buyerOrderNo;

    /** 订单类型：CARD标准商品履约，O2O配送O2O渠道，INSTORE到店购渠道 */
    @Schema(description = "订单类型：CARD标准商品履约，O2O配送O2O渠道，INSTORE到店购渠道", requiredMode = Schema.RequiredMode.REQUIRED, example = "CARD")
    private String orderType;

    /** 公共主状态：0已创建，10处理中，20部分成功，30成功，40失败，50已取消，90人工处理 */
    @Schema(description = "公共主状态：0已创建，10处理中，20部分成功，30成功，40失败，50已取消，90人工处理", requiredMode = Schema.RequiredMode.REQUIRED, example = "PROCESSING")
    private SupplyChainOrderStatus orderStatus;

    /** 售后状态：0无售后，10处理中，20部分退款，30全部退款，90人工处理 */
    @Schema(description = "售后状态：0无售后，10处理中，20部分退款，30全部退款，90人工处理", requiredMode = Schema.RequiredMode.REQUIRED, example = "NONE")
    private SupplyChainAftersaleStatus aftersaleStatus;

    /** 当前业务阶段稳定编码 */
    @Schema(description = "当前业务阶段稳定编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SUPPLIER_PROCESSING")
    private String businessStage;

    /** 已投影到总表的场景详情版本号；商城据此拒绝乱序回退 */
    @Schema(description = "已投影到总表的场景详情版本号；商城据此拒绝乱序回退", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "3")
    private Long detailVersion;

    /** 列表展示供应商编码快照 */
    @Schema(description = "列表展示供应商编码快照", example = "QNM")
    private String supplierCode;

    /** 列表展示供应商名称快照 */
    @Schema(description = "列表展示供应商名称快照", example = "奇那梦")
    private String supplierName;

    /** 订单最后更新时间，Asia/Shanghai */
    @Schema(description = "订单最后更新时间，Asia/Shanghai", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:31:00Z")
    private OffsetDateTime updatedAt;
}
