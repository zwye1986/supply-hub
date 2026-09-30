package com.aifuli.module.supplychain.integration.buyer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 采购项目集成能力状态，与 DDL 状态码保持一致。 */
@Getter
@RequiredArgsConstructor
public enum BuyerIntegrationCapabilityStatus {
    ENABLED((short) 10),
    DISABLED((short) 20);

    private final short code;

    public boolean matches(Short value) {
        return value != null && value == code;
    }
}
