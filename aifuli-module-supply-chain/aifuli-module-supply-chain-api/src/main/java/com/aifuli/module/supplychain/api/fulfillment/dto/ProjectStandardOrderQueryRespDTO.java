package com.aifuli.module.supplychain.api.fulfillment.dto;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目标准订单封闭响应。设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#1-分层与可信输入。
 */
@Data
public class ProjectStandardOrderQueryRespDTO {
    /** 采购商侧C端订单号，与buyer_id组成永久受理键 */
    private String buyerOrderNo;

    /** 受理回执稳定业务编号 */
    private String requestId;

    /** 回执状态：0 PROCESSING处理中，10 ACCEPTED已受理，20 REJECTED_RETRYABLE条件修复后可重试，30 REJECTED_FINAL最终拒绝 */
    private FulfillmentReceiptStatus receiptStatus;

    /** 回执状态及条件重试CAS版本号 */
    private Long receiptVersion;

    /** 拒绝原因稳定错误码，处理中或已受理时为空 */
    private String rejectionCode;

    /** 供应链总订单稳定业务编号 */
    private String orderNo;

    /** 公共主状态：0已创建，10处理中，20部分成功，30成功，40失败，50已取消，90人工处理 */
    private SupplyChainOrderStatus orderStatus;

    /** 履约请求状态：0待路由，10路由中，20履约中，40成功，50失败，90人工处理；一期不支持部分成功 */
    private FulfillmentStatus fulfillmentStatus;

    /** 请求履约数量 */
    private Long requestedQuantity;

    /** 已经成功交付的数量 */
    private Long deliveredQuantity;

    /** 存在可授权交付的卡券资产 */
    private Boolean assetAvailable;

    /** 受理成功时间，Asia/Shanghai */
    private OffsetDateTime acceptedAt;

    /** 订单进入业务终态时间，Asia/Shanghai */
    private OffsetDateTime completedAt;

    /** 订单最后更新时间，Asia/Shanghai */
    private OffsetDateTime updatedAt;
}
