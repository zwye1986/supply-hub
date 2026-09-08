package com.aifuli.channel.taobaoflash.dto;

import lombok.Data;

/**
 * 中台 CHANNEL_NOTIFY 投递的退款通知 payload。
 *
 * <p>字段对齐中台 {@code TaobaoFlashChannelPort.ChannelRefundNotify}：
 * channelCode/channelRefundNo/transactionId/refundAmount/refundStatus/traceId。
 * refundStatus 取中台 O2ORefundStatus 枚举名（如 "SUCCESS"/"FAILED"）。详见
 * docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4。</p>
 */
@Data
public class ChannelRefundNotifyPayload {

    private String channelCode;

    private String channelRefundNo;

    private String transactionId;

    /** 退款金额（分） */
    private Long refundAmount;

    /** 退款状态：中台 O2ORefundStatus 枚举名（SUCCESS/FAILED/QUEUED 等） */
    private String refundStatus;

    private String traceId;
}
