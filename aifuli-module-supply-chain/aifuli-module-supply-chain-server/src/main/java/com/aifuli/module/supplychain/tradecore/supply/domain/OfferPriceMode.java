package com.aifuli.module.supplychain.tradecore.supply.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 合作商品供应价模式。设计见规格 15.7。 */
@Getter
@RequiredArgsConstructor
public enum OfferPriceMode {
    FIXED("固定值"),
    REALTIME("实时更新"),
    DISCOUNT("按供货折扣");

    private final String chineseName;

    public static OfferPriceMode fromCode(String code) {
        for (OfferPriceMode value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown offer price mode: " + code);
    }
}
