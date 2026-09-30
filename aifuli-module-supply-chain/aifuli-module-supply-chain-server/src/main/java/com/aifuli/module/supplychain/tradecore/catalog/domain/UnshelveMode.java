package com.aifuli.module.supplychain.tradecore.catalog.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 商品下架方式枚举。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#28-商品管理列表端模型增强与类型优化030-changeset}。
 */
@Getter
@RequiredArgsConstructor
public enum UnshelveMode {
    MANUAL_UNSHELVE("MANUAL_UNSHELVE", "手动下架"),
    SYSTEM_UNSHELVE("SYSTEM_UNSHELVE", "系统下架");

    private final String code;
    private final String chineseName;

    public static UnshelveMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (UnshelveMode mode : values()) {
            if (mode.code.equalsIgnoreCase(code.trim())) {
                return mode;
            }
        }
        return null;
    }
}
