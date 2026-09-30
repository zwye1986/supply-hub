package com.aifuli.module.supplychain.tradecore.catalog.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 采购商商品生命周期。 */
@Getter
@RequiredArgsConstructor
public enum BuyerListingStatus {
    DRAFT((short) 0, "草稿"),
    PUBLISHED((short) 10, "已发布"),
    SUSPENDED((short) 20, "已暂停"),
    RETIRED((short) 90, "已退役");

    private final short code;
    private final String chineseName;

    public static BuyerListingStatus fromCode(short code) {
        for (BuyerListingStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown buyer listing status: " + code);
    }

    public boolean canTransitionTo(BuyerListingStatus target) {
        if (this == RETIRED || this == target) {
            return false;
        }
        return switch (this) {
            case DRAFT -> target == PUBLISHED || target == RETIRED;
            case PUBLISHED -> target == SUSPENDED || target == RETIRED;
            case SUSPENDED -> target == PUBLISHED || target == RETIRED;
            case RETIRED -> false;
        };
    }
}
