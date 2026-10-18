package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 原单冻结的非秘密槽身份；版本保留完整String，不与凭据集项的Integer版本混用。
 * 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#23-凭据。
 */
public record FrozenCredentialSlotIdentity(long slotId, String providerType, String activeSecretVersion) {
    public FrozenCredentialSlotIdentity {
        if (slotId <= 0 || providerType == null || providerType.isBlank()
                || activeSecretVersion == null || activeSecretVersion.isBlank()) {
            throw new IllegalArgumentException("冻结凭据槽身份不完整");
        }
    }
}
