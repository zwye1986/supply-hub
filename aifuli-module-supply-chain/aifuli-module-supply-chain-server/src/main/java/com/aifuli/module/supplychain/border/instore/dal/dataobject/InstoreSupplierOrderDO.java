package com.aifuli.module.supplychain.border.instore.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 到店购固定商户供应商订单 DO，对应 {@code sc_instore_supplier_order}。
 *
 * <p>保存到店购场景支付成功且资金处理后为固定外部商户（例如大润发）创建的供应商履约订单与结算事实。
 * 对标 sc_o2o_supplier_order；到店即完成，支付成功即履约成功，无 225 推送与渠道通知收敛。
 * 一笔采购订单至多一张有效成功供应商订单；active_flag 与 effective_success_flag 由唯一约束保证。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构ddl-新增-012-instore-channelsql}。</p>
 */
@TableName("sc_instore_supplier_order")
@Data
@Builder
public class InstoreSupplierOrderDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String supplierOrderNo;
    private Long orderId;
    private Long instoreOrderId;
    private Long paymentFlowId;
    private Long buyerId;
    private Long supplierId;
    private Long supplierChannelBindingId;
    private String supplierCodeSnapshot;
    private String supplierNameSnapshot;
    private String channelCode;
    private String channelOrderId;
    private String tradeId;
    private String settlementMode;
    private Long channelPayAmount;
    private Long supplierSettlementAmount;
    private String currency;
    private Short fulfillmentStatus;
    private Short settlementStatus;
    private String externalFulfillmentNo;
    /** 活动尝试标记：1 非终态，NULL 终态；由唯一约束保证同一采购订单只有一个活动供应商订单。 */
    private Byte activeFlag;
    /** 有效成功标记：1 唯一有效成功，NULL 其他；由唯一约束保证同一采购订单至多一张有效成功。 */
    private Byte effectiveSuccessFlag;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
