package com.aifuli.module.supplychain.integration.buyer;

import cn.hutool.crypto.digest.DigestUtil;

/**
 * 采购商集成日志脱敏工具：把外部租户标识压成不可逆短摘要。
 *
 * <p>日志只允许输出 SHA-256 前 12 位十六进制摘要，禁止完整 externalTenantId；
 * 会员手机号、姓名和 buyerUserId 一律不得进入日志。设计见
 * {@code docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#4-日志与异常边界}。</p>
 */
public final class BuyerIntegrationLogSanitizer {

    private static final int DIGEST_PREFIX_LENGTH = 12;

    /**
     * 输出外部租户标识的不可逆短摘要；null 输入返回固定占位。
     *
     * @param externalTenantId SaaS 外部租户路由标识
     * @return 12 位十六进制摘要或 NONE
     */
    public static String externalTenantDigest(String externalTenantId) {
        if (externalTenantId == null) {
            return "NONE";
        }
        return DigestUtil.sha256Hex(externalTenantId).substring(0, DIGEST_PREFIX_LENGTH);
    }

    private BuyerIntegrationLogSanitizer() {
    }
}
