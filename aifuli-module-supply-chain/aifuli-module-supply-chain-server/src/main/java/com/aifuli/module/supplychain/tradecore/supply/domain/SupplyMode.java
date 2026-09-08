package com.aifuli.module.supplychain.tradecore.supply.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 供货模式：封闭五值枚举，与前端管理端及 DDL CHECK 对齐。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#41-供货模式-supplymode}
 */
@Getter
@RequiredArgsConstructor
public enum SupplyMode {
    API_DIRECT("API直发"),
    DROP_SHIP("一件代发"),
    BATCH("批量发货"),
    PICKUP("门店自提"),
    H5("H5直发");

    private final String chineseName;

    public static SupplyMode fromCode(String code) {
        for (SupplyMode value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown supply mode: " + code);
    }
}
