package com.aifuli.module.supplychain.tradecore.buyer.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 采购商管理生命周期。
 *
 * <p>黑名单可逆，不等于归档。设计见
 * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum BuyerStatus {
    ACTIVE((short) 10, "启用"),
    SUSPENDED((short) 20, "暂停"),
    BLACKLIST((short) 30, "黑名单"),
    ARCHIVED((short) 90, "已归档");

    private final short code;
    private final String chineseName;

    public static BuyerStatus fromCode(short code) {
        for (BuyerStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown buyer status: " + code);
    }

    public boolean canTransitionTo(BuyerStatus target) {
        if (this == ARCHIVED || this == target) {
            return false;
        }
        return switch (this) {
            case ACTIVE -> target == SUSPENDED || target == BLACKLIST || target == ARCHIVED;
            case SUSPENDED -> target == ACTIVE || target == BLACKLIST || target == ARCHIVED;
            case BLACKLIST -> target == ACTIVE || target == SUSPENDED;
            case ARCHIVED -> false;
        };
    }
}
