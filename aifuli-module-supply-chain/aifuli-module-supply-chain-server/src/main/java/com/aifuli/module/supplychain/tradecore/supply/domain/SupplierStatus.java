package com.aifuli.module.supplychain.tradecore.supply.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 供应商管理生命周期。
 *
 * <p>黑名单可逆，不等于归档。设计见
 * {@code docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#151-合作状态}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum SupplierStatus {
    ACTIVE((short) 10, "启用"),
    SUSPENDED((short) 20, "暂停"),
    BLACKLIST((short) 30, "黑名单"),
    ARCHIVED((short) 90, "已归档");

    private final short code;
    private final String chineseName;

    public static SupplierStatus fromCode(short code) {
        for (SupplierStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown supplier status: " + code);
    }

    public boolean canTransitionTo(SupplierStatus target) {
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
