package com.aifuli.module.supplychain.tradecore.supply.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商合作状态机。设计见
 * {@code docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#151-合作状态}。
 */
class SupplierStatusTest {

    @Test
    void blacklistIsReversibleAndNotArchive() {
        assertThat(SupplierStatus.ACTIVE.canTransitionTo(SupplierStatus.BLACKLIST)).isTrue();
        assertThat(SupplierStatus.SUSPENDED.canTransitionTo(SupplierStatus.BLACKLIST)).isTrue();
        assertThat(SupplierStatus.BLACKLIST.canTransitionTo(SupplierStatus.ACTIVE)).isTrue();
        assertThat(SupplierStatus.BLACKLIST.canTransitionTo(SupplierStatus.SUSPENDED)).isTrue();
        assertThat(SupplierStatus.BLACKLIST.canTransitionTo(SupplierStatus.ARCHIVED)).isFalse();
        assertThat(SupplierStatus.ACTIVE.canTransitionTo(SupplierStatus.ARCHIVED)).isTrue();
        assertThat(SupplierStatus.SUSPENDED.canTransitionTo(SupplierStatus.ACTIVE)).isTrue();
        assertThat(SupplierStatus.ARCHIVED.canTransitionTo(SupplierStatus.ACTIVE)).isFalse();
        assertThat(SupplierStatus.fromCode((short) 30)).isEqualTo(SupplierStatus.BLACKLIST);
    }
}
