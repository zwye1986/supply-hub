package com.aifuli.module.supplychain.border.instore.domain;

/**
 * 到店购入口凭证用途：首页与订单详情严格隔离，不允许跨用途消费。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#7-身份与入口}。</p>
 */
public enum InstoreEntryCredentialPurpose {
    HOME,
    DETAIL
}
