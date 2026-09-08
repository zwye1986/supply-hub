package com.aifuli.module.supplychain.border.o2o.domain;

/**
 * O2O 外部操作幂等键前缀与构造，避免 Lifecycle 内散落业务字面量。
 *
 * <p>O2O 支付永久幂等键为 (channelCode, transactionId)，退款为 (channelCode, refundNo)。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
 */
public final class O2OIdempotencyKeys {

    private O2OIdempotencyKeys() {
    }

    /** PREORDER 操作幂等键：同一渠道交易号只能抢占一次收银台。 */
    public static String preorder(String channelCode, String transactionId) {
        return "PREORDER:" + channelCode + ":" + transactionId;
    }

    /** 支付查单幂等键：按原 outTradeNo 查单，禁止生成新支付单号。 */
    public static String paymentQuery(String outTradeNo) {
        return "PAYQ:" + outTradeNo;
    }

    /** 采购商退款幂等键：按中台退款号发起，禁止自动重退。 */
    public static String buyerRefund(String middleRefundNo) {
        return "BRFD:" + middleRefundNo;
    }

    /** 退款查单幂等键：按原 refundKey 查退款结果。 */
    public static String refundQuery(String refundKey) {
        return "RFDQ:" + refundKey;
    }

    /** 渠道通知幂等键：按渠道交易号 + 通知类型。 */
    public static String channelNotify(String channelCode, String transactionId, String notifyType) {
        return "CNOTIFY:" + channelCode + ":" + transactionId + ":" + notifyType;
    }
}
