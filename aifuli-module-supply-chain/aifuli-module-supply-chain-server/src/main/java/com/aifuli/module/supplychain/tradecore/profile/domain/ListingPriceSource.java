package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * 项目 listing 版本的价格来源：继承上级或项目覆盖。与解析器 {@link PriceSource}（L1/L2/L3）不是同一列。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
 */
public enum ListingPriceSource {
    INHERITED,
    PROJECT_OVERRIDE
}
