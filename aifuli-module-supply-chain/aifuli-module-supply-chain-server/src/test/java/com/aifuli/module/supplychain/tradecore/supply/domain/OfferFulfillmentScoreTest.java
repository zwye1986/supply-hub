package com.aifuli.module.supplychain.tradecore.supply.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稳定性/时效性默认 80，取值 0–100。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md#2-领域不变量}。
 */
class OfferFulfillmentScoreTest {

    @Test
    void defaultIsEightyWithinInclusiveRange() {
        assertThat(OfferFulfillmentScore.DEFAULT).isEqualTo(80);
        assertThat(OfferFulfillmentScore.isValid(OfferFulfillmentScore.DEFAULT)).isTrue();
        assertThat(OfferFulfillmentScore.isValid(OfferFulfillmentScore.MIN)).isTrue();
        assertThat(OfferFulfillmentScore.isValid(OfferFulfillmentScore.MAX)).isTrue();
        assertThat(OfferFulfillmentScore.isValid(OfferFulfillmentScore.MIN - 1)).isFalse();
        assertThat(OfferFulfillmentScore.isValid(OfferFulfillmentScore.MAX + 1)).isFalse();
        assertThat(OfferFulfillmentScore.isValid(null)).isFalse();
    }
}
