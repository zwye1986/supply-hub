package com.aifuli.module.supplychain.tradecore.account.domain;

/**
 * 跨模块金额值对象；金额单位固定为 CNY 分。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public record Money(long cents, String currency) {

    /** 一期唯一允许币种，与 DDL {@code currency = 'CNY'} 一致。 */
    public static final String CURRENCY_CNY = "CNY";

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("Money cannot be negative");
        }
        if (!CURRENCY_CNY.equals(currency)) {
            throw new IllegalArgumentException("M1 only supports CNY amounts");
        }
    }

    public static Money cny(long cents) {
        return new Money(cents, CURRENCY_CNY);
    }
}
