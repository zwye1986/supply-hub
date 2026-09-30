package com.aifuli.channel.taobaoflash.dto;

import lombok.Data;

/**
 * 中台 CHANNEL_NOTIFY 投递的支付通知 payload。
 *
 * <p>字段对齐中台 {@code TaobaoFlashChannelPort.ChannelPaymentNotify}：
 * channelCode/transactionId/outTradeNo/payAmount/paymentStatus/traceId。
 * paymentStatus 取中台 O2OPaymentStatus 枚举名（如 "PAID"）。详见
 * docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4。</p>
 */
@Data
public class ChannelPaymentNotifyPayload {

    private String channelCode;

    private String transactionId;

    private String outTradeNo;

    /** 支付金额（分） */
    private Long payAmount;

    /** 支付状态：中台 O2OPaymentStatus 枚举名（PAID/FAILED/CLOSED 等） */
    private String paymentStatus;

    private String traceId;
}
