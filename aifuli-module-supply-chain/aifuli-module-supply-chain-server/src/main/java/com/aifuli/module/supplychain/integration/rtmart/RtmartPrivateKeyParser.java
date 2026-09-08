package com.aifuli.module.supplychain.integration.rtmart;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析旅通签名所需的 PKCS#8 RSA 私钥。
 *
 * <p>部署配置可使用 PEM 包装或移除空白后的裸 Base64。Base64/JCA 解析失败保留实际 cause 供诊断，
 * 绝不回显输入内容。设计见
 * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#43-签名规则}。</p>
 */
final class RtmartPrivateKeyParser {

    private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_END = "-----END PRIVATE KEY-----";
    private static final Pattern COMPLETE_PEM_PATTERN = Pattern.compile(
            "\\A" + PEM_BEGIN + "\\s+([A-Za-z0-9+/=\\s]+?)\\s+" + PEM_END + "\\s*\\z");

    private RtmartPrivateKeyParser() {
    }

    /**
     * 将 PKCS#8 PEM 或裸 Base64 私钥转换为 RSA 私钥。
     *
     * @param configuredPrivateKey 私密配置，只能在本方法内存中短暂处理
     * @return 可用于 {@code SHA1withRSA} 的 RSA 私钥
     * @throws RtmartEntryException 配置为空、Base64 非法或不是 RSA PKCS#8 时抛出
     */
    static PrivateKey parsePkcs8(String configuredPrivateKey) {
        if (configuredPrivateKey == null || configuredPrivateKey.isBlank()) {
            throw RtmartEntryException.configuration();
        }
        try {
            String normalized = normalizePkcs8Base64(configuredPrivateKey);
            byte[] encodedKey = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw RtmartEntryException.configuration(exception);
        }
    }

    /**
     * 严格区分唯一、完整的 PKCS#8 PEM 包装与不含任何 PEM 边界的裸 Base64。
     *
     * <p>禁止通过全局 replace “修复”片段、重复或混合边界，以免错误配置悄然被解释为另一把密钥。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#43-签名规则}。</p>
     */
    private static String normalizePkcs8Base64(String configuredPrivateKey) {
        String trimmed = configuredPrivateKey.trim();
        if (containsPemBoundary(trimmed)) {
            Matcher matcher = COMPLETE_PEM_PATTERN.matcher(trimmed);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Rtmart private key encoding is invalid");
            }
            return matcher.group(1).replaceAll("\\s+", "");
        }
        return trimmed.replaceAll("\\s+", "");
    }

    private static boolean containsPemBoundary(String value) {
        return value.contains("-----BEGIN") || value.contains("-----END");
    }
}
