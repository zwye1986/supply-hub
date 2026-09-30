package com.aifuli.module.supplychain.infrastructure.auth;

/**
 * 本地 AES-256-GCM 信封 appKey 解析器，主密钥由认证配置装配后注入。
 *
 * <p>构造时复制主密钥，避免调用方后续修改数组影响解析器；本类不缓存任何 appKey 明文。
 * 详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#63-appkeysecretresolverspi-与本地信封实现}。</p>
 */
public final class LocalEnvelopeAppKeySecretResolver implements AppKeySecretResolver {

    private final byte[] masterKey;

    public LocalEnvelopeAppKeySecretResolver(byte[] masterKey) {
        this.masterKey = masterKey.clone();
    }

    /**
     * 按 appId 与 keyVersion 绑定的 AAD 解密；明文只作为本次调用返回值，不在解析器内留存。
     */
    @Override
    public String resolveAppKey(String appId, int keyVersion, String secretRef) {
        return LocalEnvelopeAppKeyCodec.decrypt(masterKey, appId, keyVersion, secretRef);
    }
}
