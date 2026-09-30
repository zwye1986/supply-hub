package com.aifuli.module.supplychain.border.instore.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 到店购渠道订单头 DO，对应 {@code sc_instore_channel_order}。
 *
 * <p>与总订单 1:1 的场景详情，承载旅通 order_id；当前生产不变量要求与支付卫星 1:1。
 * 受理时同时固化采购项目、收银台档案版本和 SaaS 租户路由快照，不从实际 HTTP 调用方推断归属。
 * 唯一约束 (channel_code, channel_order_id) 为渠道维度，不以 Buyer 缩小范围。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构ddl-新增-012-instore-channelsql}。</p>
 */
@TableName("sc_instore_channel_order")
@Data
@Builder
public class InstoreChannelOrderDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String instoreOrderNo;
    private Long orderId;
    private Long buyerId;
    /** 受理时固化的采购项目内部主键。 */
    private Long projectId;
    /** 受理时固化的 CASHIER 集成档案版本内部主键。 */
    private Long cashierProfileVersionId;
    /** 受理时固化的采购商来源系统编码。 */
    private String sourceSystem;
    /** 受理时固化的 SaaS 商城租户唯一标识。 */
    private String externalTenantId;
    /** 受理时固化的 SaaS 商城会员唯一标识，不透明字符串。 */
    private String buyerUserId;
    private String channelCode;
    private Long channelUserBindingId;
    /** 013 历史兼容快照；014 起新建订单为空。 */
    private String channelUserId;
    private String channelOrderId;
    private String companyCode;
    private Long supplierChannelBindingId;
    private Long supplierId;
    private String supplierCodeSnapshot;
    private String supplierNameSnapshot;
    private String externalStoreId;
    private String externalStoreName;
    private String storeSnapshotJson;
    private Long totalAmount;
    private Long totalPaidAmount;
    private String currency;
    private Long totalChannelRefundAmount;
    private Long totalBuyerRefundAmount;
    private Long totalAccountReturnAmount;
    private Long activeRefundCaseId;
    private Short orderStatus;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
