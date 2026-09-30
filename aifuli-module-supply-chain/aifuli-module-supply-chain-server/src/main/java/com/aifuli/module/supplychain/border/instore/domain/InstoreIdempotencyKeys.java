package com.aifuli.module.supplychain.border.instore.domain;

/**
 * 到店购外部操作幂等键前缀与构造，避免 Lifecycle 内散落业务字面量。
 *
 * <p>到店购支付永久幂等键为 (channelCode, trade_id)，退款为 (channelCode, refund_bn)，
 * 订单头为 (channelCode, order_id)。详见
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#5-入站契约中台-api}。</p>
 */
public final class InstoreIdempotencyKeys {

    private InstoreIdempotencyKeys() {
    }

    /** PREORDER 操作幂等键：同一渠道支付流水号只能建单一次。 */
    public static String preorder(String channelCode, String tradeId) {
        return "PREORDER:" + channelCode + ":" + tradeId;
    }

    /** 支付查单幂等键：按原 outTradeNo 查单，禁止生成新支付单号。 */
    public static String paymentQuery(String outTradeNo) {
        return "IPAYQ:" + outTradeNo;
    }

    /** 取消支付流水幂等键：按原 trade_id 取消，禁止重复取消。 */
    public static String cancelPay(String channelCode, String tradeId) {
        return "CANCEL:" + channelCode + ":" + tradeId;
    }

    /** 采购商退款幂等键：按中台退款号发起，禁止自动重退。 */
    public static String buyerRefund(String middleRefundNo) {
        return "IBRFD:" + middleRefundNo;
    }

    /** 退款查单幂等键：按原 refund_bn 查退款结果。 */
    public static String refundQuery(String channelCode, String refundBn) {
        return "IRFDQ:" + channelCode + ":" + refundBn;
    }

    /**
     * 取消已支付转退款的渠道退款号：按 trade_id 稳定，禁止带 sequence。
     *
     * <p>联调切片同 trade_id 第二次取消必须回放已有 Case。
     * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
     */
    public static String cancelRefundChannelNo(String tradeId) {
        return "CANCEL:" + tradeId;
    }
}
