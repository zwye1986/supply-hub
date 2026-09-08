package com.aifuli.module.supplychain.integration.buyer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 采购项目集成档案状态，与 DDL 状态码保持一致。 */
@Getter
@RequiredArgsConstructor
public enum BuyerIntegrationProfileStatus {
    ACTIVE((short) 10),
    SUSPENDED((short) 20),
    ARCHIVED((short) 90);

    private final short code;

    public boolean matches(Short value) {
        return value != null && value == code;
    }
}
