package com.aifuli.module.supplychain.tradecore.catalog.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 品牌、类目、SPU 与 SKU 共用的管理生命周期。 */
@Getter
@RequiredArgsConstructor
public enum CatalogEntityStatus {
    DRAFT((short) 0, "草稿"),
    ACTIVE((short) 10, "启用"),
    INACTIVE((short) 20, "停用"),
    ARCHIVED((short) 90, "已归档");

    private final short code;
    private final String chineseName;

    public static CatalogEntityStatus fromCode(short code) {
        for (CatalogEntityStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown catalog entity status: " + code);
    }

    public boolean canTransitionTo(CatalogEntityStatus target) {
        if (this == ARCHIVED || this == target) {
            return false;
        }
        return switch (this) {
            case DRAFT -> target == ACTIVE || target == ARCHIVED;
            case ACTIVE -> target == INACTIVE || target == ARCHIVED;
            case INACTIVE -> target == ACTIVE || target == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}
