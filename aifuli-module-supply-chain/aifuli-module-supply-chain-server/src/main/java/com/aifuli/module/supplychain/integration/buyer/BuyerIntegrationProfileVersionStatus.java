package com.aifuli.module.supplychain.integration.buyer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 采购项目集成档案版本状态，与 DDL 状态码保持一致。 */
@Getter
@RequiredArgsConstructor
public enum BuyerIntegrationProfileVersionStatus {
    DRAFT((short) 0),
    VALIDATED((short) 5),
    PUBLISHED((short) 10),
    SUPERSEDED((short) 20),
    RETIRED((short) 90);

    private final short code;

    public boolean matches(Short value) {
        return value != null && value == code;
    }
}
