package com.aifuli.module.supplychain.tradecore.profile.domain;

/** 发货时效封闭枚举。设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。 */
public enum ShippingSlaCode {
    SAME_DAY,
    NEXT_DAY,
    HOURS_48,
    HOURS_72,
    CUSTOM
}
