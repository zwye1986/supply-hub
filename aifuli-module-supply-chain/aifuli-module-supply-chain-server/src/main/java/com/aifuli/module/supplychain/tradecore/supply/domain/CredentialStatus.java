package com.aifuli.module.supplychain.tradecore.supply.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 凭据集与凭据项生命周期状态（AD-3，变更集 054）。
 *
 * <p>仅 {@link #ACTIVE} 的集与项参与 {@code CredentialSetResolver.resolveSet} 解析；
 * 停用/归档项被跳过，停用/归档集整体拒绝解析。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#52-ad-3-凭据集变更集-054-envelope-provider}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum CredentialStatus {
    ACTIVE((short) 10, "可用"),
    DISABLED((short) 20, "停用"),
    ARCHIVED((short) 90, "归档");

    private final short code;
    private final String chineseName;

    public static CredentialStatus fromCode(short code) {
        for (CredentialStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown credential status: " + code);
    }
}
