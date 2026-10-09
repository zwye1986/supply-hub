package com.aifuli.module.supplychain.border.fulfillment.domain;

/** 版本快照中的非秘密凭据身份，不能用当前项版本替代；见华昇生产闭环设计§3.3。 */
public record FrozenCredentialItemIdentity(long itemId, String itemKey, String providerType, int activeSecretVersion) {
    public FrozenCredentialItemIdentity {
        if (itemId <= 0 || itemKey == null || itemKey.isBlank() || providerType == null || providerType.isBlank() || activeSecretVersion < 0) {
            throw new IllegalArgumentException("冻结凭据身份不完整");
        }
    }
}
