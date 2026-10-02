package com.aifuli.module.supplychain.infrastructure.auth;

/**
 * 项目应用密钥发码与密文落库 SPI；自定义 AppKeySecretResolver 时必须提供兼容的发码实现。
 * 设计见 docs/superpowers/specs/2026-09-29-access-config-backend-impl-design.md#31-发码材料与安全生命周期。
 */
public interface AppKeySecretIssuer {

    IssueMaterial issue(String appId, int keyVersion);

    /** 仅在一次性响应中使用 appKey；对象日志不得泄露明文或存储引用。 */
    record IssueMaterial(String appKey, String secretRef, String maskedHint) {
        @Override
        public String toString() {
            return "IssueMaterial[maskedHint=" + maskedHint + ']';
        }
    }
}
