package com.aifuli.module.supplychain.integration.taobaoflash;

/**
 * 淘宝闪购渠道服务出站端口：中台通过该 Port 向渠道服务投递类型化渠道通知。
 *
 * <p>渠道服务收到通知后调用 ele.me SDK 的 payCallback/refundCallback。
 * 中台不接收或透传任意 URL、Header 或 JSON，只发送类型化命令。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
public interface TaobaoFlashChannelPort {

    /** 通知渠道支付结果（ele.me payCallback）。 */
    void notifyPayment(ChannelPaymentNotify notify);

    /** 通知渠道退款结果（ele.me refundCallback）。 */
    void notifyRefund(ChannelRefundNotify notify);

    /** 支付通知命令。 */
    record ChannelPaymentNotify(String channelCode, String transactionId, String outTradeNo,
                                 long payAmount, String paymentStatus, String traceId) {
    }

    /** 退款通知命令。 */
    record ChannelRefundNotify(String channelCode, String channelRefundNo, String transactionId,
                                long refundAmount, String refundStatus, String traceId) {
    }
}
