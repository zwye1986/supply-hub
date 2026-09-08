package com.aifuli.module.supplychain.border.o2o.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O2O 幂等键构造：支付按 (channelCode, transactionId)，退款按中台退款号，查单按原单号。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
 */
class O2OIdempotencyKeysTest {

    @Test
    void buildsStableKeysWithoutNewBusinessNumbers() {
        assertThat(O2OIdempotencyKeys.preorder("ELEME", "ALSC001"))
                .isEqualTo("PREORDER:ELEME:ALSC001");
        assertThat(O2OIdempotencyKeys.paymentQuery("OT20260813001"))
                .isEqualTo("PAYQ:OT20260813001");
        assertThat(O2OIdempotencyKeys.buyerRefund("ORC01HABCDEF"))
                .isEqualTo("BRFD:ORC01HABCDEF");
        assertThat(O2OIdempotencyKeys.refundQuery("RFK-1"))
                .isEqualTo("RFDQ:RFK-1");
        assertThat(O2OIdempotencyKeys.channelNotify("ELEME", "ALSC001", "PAYMENT"))
                .isEqualTo("CNOTIFY:ELEME:ALSC001:PAYMENT");
    }
}
