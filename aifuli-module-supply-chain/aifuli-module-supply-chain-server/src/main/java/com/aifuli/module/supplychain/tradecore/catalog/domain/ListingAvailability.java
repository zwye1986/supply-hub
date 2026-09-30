package com.aifuli.module.supplychain.tradecore.catalog.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 采购商商品供给可用性投影。 */
@Getter
@RequiredArgsConstructor
public enum ListingAvailability {
    AVAILABLE((short) 10, "可用"),
    UNAVAILABLE((short) 20, "不可用"),
    UNKNOWN((short) 30, "未知");

    private final short code;
    private final String chineseName;

    public static ListingAvailability fromCode(short code) {
        for (ListingAvailability availability : values()) {
            if (availability.code == code) {
                return availability;
            }
        }
        throw new IllegalArgumentException("Unknown listing availability: " + code);
    }
}
