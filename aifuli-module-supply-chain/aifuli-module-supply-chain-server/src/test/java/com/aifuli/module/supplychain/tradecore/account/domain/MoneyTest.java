package com.aifuli.module.supplychain.tradecore.account.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 金额值对象不变量：单位为 CNY 分，禁止负数与非 CNY。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁}。</p>
 */
class MoneyTest {

    @Test
    void acceptsZeroAndPositiveCnyCents() {
        assertThat(Money.cny(0)).isEqualTo(new Money(0L, Money.CURRENCY_CNY));
        assertThat(Money.cny(10_000L).cents()).isEqualTo(10_000L);
        assertThat(Money.cny(10_000L).currency()).isEqualTo("CNY");
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> Money.cny(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void rejectsNonCnyCurrency() {
        assertThatThrownBy(() -> new Money(100L, "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CNY");
        assertThatThrownBy(() -> new Money(100L, "POINT"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
