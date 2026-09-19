package com.aifuli.module.supplychain.tradecore.supply.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 供应商秘密材料引用槽状态。
 *
 * <p>对应 {@code sc_credential_slot.status}：10启用，20暂停，90已归档。</p>
 */
@Getter
@RequiredArgsConstructor
public enum CredentialSlotStatus {
    ACTIVE((short) 10, "启用"),
    SUSPENDED((short) 20, "暂停"),
    ARCHIVED((short) 90, "已归档");

    private final short code;
    private final String chineseName;

    public static CredentialSlotStatus fromCode(short code) {
        for (CredentialSlotStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown credential slot status: " + code);
    }
}
