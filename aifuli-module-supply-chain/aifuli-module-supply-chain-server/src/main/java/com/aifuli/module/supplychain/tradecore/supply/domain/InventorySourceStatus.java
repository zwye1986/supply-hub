package com.aifuli.module.supplychain.tradecore.supply.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 库存来源生命周期。 */
@Getter
@RequiredArgsConstructor
public enum InventorySourceStatus {
    DRAFT((short) 0, "草稿"),
    ACTIVE((short) 10, "启用"),
    SUSPENDED((short) 20, "暂停"),
    RETIRED((short) 90, "已退役");

    private final short code;
    private final String chineseName;
}
