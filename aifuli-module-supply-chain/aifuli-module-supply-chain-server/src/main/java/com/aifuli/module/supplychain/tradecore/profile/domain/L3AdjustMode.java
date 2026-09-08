package com.aifuli.module.supplychain.tradecore.profile.domain;

/** L3 调价方式封闭枚举。设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。 */
public enum L3AdjustMode {
    FOLLOW_L2,
    EQUAL_L1,
    CUSTOM
}
