package com.aifuli.module.supplychain.tradecore.supply.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 供应商配送区域类型。设计见规格 15.9。 */
@Getter
@RequiredArgsConstructor
public enum DeliveryRegionType {
    NATIONWIDE("全国"),
    DESIGNATED("指定区域");

    private final String chineseName;

    public static DeliveryRegionType fromCode(String code) {
        for (DeliveryRegionType value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown delivery region type: " + code);
    }
}
