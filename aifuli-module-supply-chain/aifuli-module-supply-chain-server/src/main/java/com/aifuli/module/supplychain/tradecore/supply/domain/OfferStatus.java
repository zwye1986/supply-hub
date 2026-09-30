package com.aifuli.module.supplychain.tradecore.supply.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 供应商供给生命周期。 */
@Getter
@RequiredArgsConstructor
public enum OfferStatus {
    DRAFT((short) 0, "草稿"),
    PUBLISHED((short) 10, "已发布"),
    SUSPENDED((short) 20, "已暂停"),
    RETIRED((short) 90, "已退役");

    private final short code;
    private final String chineseName;

    public static OfferStatus fromCode(short code) {
        for (OfferStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown offer status: " + code);
    }
}
