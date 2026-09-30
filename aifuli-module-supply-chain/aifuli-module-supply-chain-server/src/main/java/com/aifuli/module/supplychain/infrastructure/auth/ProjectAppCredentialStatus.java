package com.aifuli.module.supplychain.infrastructure.auth;

/**
 * 项目应用凭证版本状态，与 016 DDL CHECK 约束语义一致。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
public enum ProjectAppCredentialStatus {

    /** 当前密钥。 */
    CURRENT,
    /** 轮换过渡密钥，验签可用。 */
    GRACE,
    /** 已吊销。 */
    REVOKED,
    /** 已过期。 */
    EXPIRED;

    /** 判断状态字符串是否为验签可用的 CURRENT/GRACE。 */
    public static boolean isUsable(String status) {
        return CURRENT.name().equals(status) || GRACE.name().equals(status);
    }
}
