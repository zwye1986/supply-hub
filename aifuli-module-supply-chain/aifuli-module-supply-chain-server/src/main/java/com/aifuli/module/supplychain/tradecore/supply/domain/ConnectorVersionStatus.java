package com.aifuli.module.supplychain.tradecore.supply.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 连接器版本生命周期；发布和退役后配置不可修改。 */
@Getter
@RequiredArgsConstructor
public enum ConnectorVersionStatus {
    DRAFT((short) 0, "草稿"),
    VALIDATED((short) 5, "已验证"),
    PUBLISHED((short) 10, "已发布"),
    RETIRED((short) 90, "已退役");

    private final short code;
    private final String chineseName;

    public static ConnectorVersionStatus fromCode(short code) {
        for (ConnectorVersionStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown connector version status: " + code);
    }
}
