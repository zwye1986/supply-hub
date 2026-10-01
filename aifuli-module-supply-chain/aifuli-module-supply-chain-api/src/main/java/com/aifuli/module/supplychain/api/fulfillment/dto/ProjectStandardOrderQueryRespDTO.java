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
    /** 采购商订单号 */
    private String buyerOrderNo;

    /** 受理回执稳定业务编号 */
    private String requestId;

    /** 受理回执状态 */
    private FulfillmentReceiptStatus receiptStatus;

    /** 受理回执乐观锁版本号 */
    private Long receiptVersion;

    /** 稳定拒绝原因码 */
    private String rejectionCode;

    /** 供应链总订单稳定业务编号 */
    private String orderNo;

    /** 总订单公共状态 */
    private SupplyChainOrderStatus orderStatus;

    /** 履约请求公共状态 */
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

    /** 最后更新时间 */
    private OffsetDateTime updatedAt;
}
