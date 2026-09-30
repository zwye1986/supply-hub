package com.aifuli.module.supplychain.tradecore.buyer.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuyerStatusTest {

    @Test
    void blacklistIsReversibleAndCannotArchive() {
        assertThat(BuyerStatus.fromCode((short) 30)).isEqualTo(BuyerStatus.BLACKLIST);
        assertThat(BuyerStatus.ACTIVE.canTransitionTo(BuyerStatus.BLACKLIST)).isTrue();
        assertThat(BuyerStatus.SUSPENDED.canTransitionTo(BuyerStatus.BLACKLIST)).isTrue();
        assertThat(BuyerStatus.BLACKLIST.canTransitionTo(BuyerStatus.ACTIVE)).isTrue();
        assertThat(BuyerStatus.BLACKLIST.canTransitionTo(BuyerStatus.SUSPENDED)).isTrue();
        assertThat(BuyerStatus.BLACKLIST.canTransitionTo(BuyerStatus.ARCHIVED)).isFalse();
        assertThat(BuyerStatus.ACTIVE.canTransitionTo(BuyerStatus.ARCHIVED)).isTrue();
        assertThat(BuyerStatus.ARCHIVED.canTransitionTo(BuyerStatus.ACTIVE)).isFalse();
    }
}
