package com.aifuli.module.supplychain.border.instore.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 到店购幂等键构造：支付按 (channelCode, trade_id)，退款按 (channelCode, refund_bn)，查单按原单号。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#5-入站契约中台-api}。</p>
 */
class InstoreIdempotencyKeysTest {

    @Test
    void buildsStableKeysWithoutNewBusinessNumbers() {
        assertThat(InstoreIdempotencyKeys.preorder("RT_MART", "TRADE001"))
                .isEqualTo("PREORDER:RT_MART:TRADE001");
        assertThat(InstoreIdempotencyKeys.paymentQuery("IPF01HABCDEF"))
                .isEqualTo("IPAYQ:IPF01HABCDEF");
        assertThat(InstoreIdempotencyKeys.cancelPay("RT_MART", "TRADE001"))
                .isEqualTo("CANCEL:RT_MART:TRADE001");
        assertThat(InstoreIdempotencyKeys.buyerRefund("IRC01HABCDEF"))
                .isEqualTo("IBRFD:IRC01HABCDEF");
        assertThat(InstoreIdempotencyKeys.refundQuery("RT_MART", "RFN001"))
                .isEqualTo("IRFDQ:RT_MART:RFN001");
        assertThat(InstoreIdempotencyKeys.cancelRefundChannelNo("TRADE001"))
                .isEqualTo("CANCEL:TRADE001");
    }
}
