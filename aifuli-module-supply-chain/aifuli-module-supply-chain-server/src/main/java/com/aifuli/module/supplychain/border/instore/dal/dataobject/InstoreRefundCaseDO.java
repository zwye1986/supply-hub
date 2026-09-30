package com.aifuli.module.supplychain.border.instore.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 到店购退款 Case DO，对应 {@code sc_instore_refund_case}。
 *
 * <p>串行协调渠道发起或取消已支付触发的退款流程；退款挂在具体支付流水上（payment_flow_id），非订单头。
 * 永久唯一键为 (channel_code, channel_refund_no)；同一支付流水最多一个活动退款，其余按 refund_sequence 排队。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构ddl-新增-012-instore-channelsql}。</p>
 */
@TableName("sc_instore_refund_case")
@Data
@Builder
public class InstoreRefundCaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundCaseNo;
    private Long instoreOrderId;
    private Long paymentFlowId;
    private Long orderId;
    private Long buyerId;
    private String channelCode;
    private String channelRefundNo;
    private Integer refundSequence;
    private String refundType;
    private Long channelRefundAmount;
    private Long buyerActualRefundAmount;
    private Long accountReturnAmount;
    private String currency;
    private String cashierRefundReference;
    private byte[] requestFingerprint;
    private Short status;
    /** 采购商退款活动标记：1 正在退款，NULL 其他；由唯一约束保证单支付流水单活动退款。 */
    private Byte buyerRefundingFlag;
    private String resultReasonCode;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
