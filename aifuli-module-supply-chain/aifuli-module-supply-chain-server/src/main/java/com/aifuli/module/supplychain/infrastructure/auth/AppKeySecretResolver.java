package com.aifuli.module.supplychain.infrastructure.auth;

/**
 * appKey 密钥系统解析 SPI：将凭证版本记录中的 {@code secret_ref} 解析为 appKey 明文。
 *
 * <p>默认实现使用本地 AES-256-GCM 信封；后续接入 KMS 时可替换实现而不改变应用服务契约。
 * 明文只能存活于单次请求内存，禁止记录日志或缓存。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#63-appkeysecretresolverspi-与本地信封实现}。</p>
 */
public interface AppKeySecretResolver {

    /**
     * 解密 secret_ref 得到 appKey 明文。
     *
     * @param appId 项目应用稳定标识，用于 AAD 绑定校验
     * @param keyVersion 密钥版本号，用于 AAD 绑定校验
     * @param secretRef 密钥系统引用，禁止传入明文 appKey
     * @return appKey 明文，仅允许存活于本次请求内存
     * @throws IllegalArgumentException secret_ref 格式非法或解密失败（含密钥或 AAD 不匹配）
     */
    String resolveAppKey(String appId, int keyVersion, String secretRef);
}
