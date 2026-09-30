package com.aifuli.module.supplychain.border.o2o.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * O2O 退款 Case DO，对应 {@code sc_o2o_refund_case}。
 *
 * <p>串行协调渠道发起、采购商执行与中台账户返还的退款流程；永久唯一键为 (channel_code,channel_refund_no)。
 * 同一详情最多一个 BUYER_REFUNDING，其余按 refund_sequence 排队。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.3-O2ORefundCase}。</p>
 */
@TableName("sc_o2o_refund_case")
@Data
@Builder
public class O2ORefundCaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundCaseNo;
    private Long o2oDetailId;
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
    /** 采购商退款活动标记：1 正在退款，NULL 其他；由唯一约束保证单详情单活动退款。 */
    private Byte buyerRefundingFlag;
    private String resultReasonCode;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
