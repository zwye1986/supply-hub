package com.aifuli.module.supplychain.border.o2o.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * O2O 渠道订单详情 DO，对应 {@code sc_o2o_channel_detail}。
 *
 * <p>与总订单一对一的场景详情，保存渠道交易、商户、收银台、资金与履约协同状态；
 * 唯一约束 (channel_code,transaction_id) 与 out_trade_no 为渠道维度，不以 Buyer 缩小范围。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.2-O2OChannelDetail}。</p>
 */
@TableName("sc_o2o_channel_detail")
@Data
@Builder
public class O2OChannelDetailDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String channelDetailNo;
    private Long orderId;
    private Long buyerId;
    private String buyerUserId;
    private String channelCode;
    private Long channelUserBindingId;
    private String channelUserId;
    private String transactionId;
    private Long supplierChannelBindingId;
    private Long supplierId;
    private String supplierCodeSnapshot;
    private String supplierNameSnapshot;
    private String externalStoreId;
    private String externalStoreName;
    private String storeSnapshotJson;
    private String outTradeNo;
    private Long channelPayAmount;
    private Long buyerPaidAmount;
    private Long buyerSettlementAmount;
    private String currency;
    private String cashierReference;
    private Short paymentStatus;
    private Short fundStatus;
    private Short channelFulfillmentStatus;
    private Long totalChannelRefundAmount;
    private Long totalBuyerRefundAmount;
    private Long totalAccountReturnAmount;
    private Long activeRefundCaseId;
    private Short status;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
