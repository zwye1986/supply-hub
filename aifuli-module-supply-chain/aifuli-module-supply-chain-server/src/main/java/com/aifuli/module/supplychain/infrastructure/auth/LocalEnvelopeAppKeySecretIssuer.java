package com.aifuli.module.supplychain.infrastructure.auth;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 本地 AES-GCM 信封发码实现：一次生成 32 字节随机密钥，按 appId 与版本加密后存引用。
 * 设计见 docs/superpowers/specs/2026-09-29-access-config-backend-impl-design.md#31-发码材料与安全生命周期。
 */
public final class LocalEnvelopeAppKeySecretIssuer implements AppKeySecretIssuer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int APP_KEY_BYTES = 32;
    private final byte[] masterKey;

    public LocalEnvelopeAppKeySecretIssuer(byte[] masterKey) {
        if (masterKey == null || masterKey.length != 32) {
            throw new IllegalArgumentException("appKey 信封主密钥非法");
        }
        this.masterKey = masterKey.clone();
    }

    @Override
    public IssueMaterial issue(String appId, int keyVersion) {
        byte[] randomBytes = new byte[APP_KEY_BYTES];
        RANDOM.nextBytes(randomBytes);
        try {
            String appKey = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            String secretRef = LocalEnvelopeAppKeyCodec.encrypt(masterKey, appId, keyVersion, appKey);
            String maskedHint = appKey.substring(0, 4) + "****"
                    + appKey.substring(appKey.length() - 4);
            return new IssueMaterial(appKey, secretRef, maskedHint);
        } finally {
            Arrays.fill(randomBytes, (byte) 0);
        }
    }
}
