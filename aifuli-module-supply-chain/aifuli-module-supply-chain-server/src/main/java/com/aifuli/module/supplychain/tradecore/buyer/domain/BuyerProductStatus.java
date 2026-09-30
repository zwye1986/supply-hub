package com.aifuli.module.supplychain.tradecore.buyer.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 分销商品状态：10有效 90已移除。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md}。
 */
@Getter
@RequiredArgsConstructor
public enum BuyerProductStatus {
    ACTIVE((short) 10, "有效"),
    REMOVED((short) 90, "已移除");

    private final short code;
    private final String chineseName;
}
