package com.aifuli.module.supplychain.border.rtmart;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析旅通入站验签所需的 X.509 RSA 公钥。
 *
 * <p>部署配置可使用 PEM 包装（{@code -----BEGIN PUBLIC KEY-----}）或移除空白后的裸 Base64。
 * Base64/JCA 解析失败抛出固定摘要的 {@link IllegalStateException}，异常 cause 保留类型与
 * 栈帧，绝不回显输入内容。与出站私钥解析 {@code RtmartPrivateKeyParser} 对称。</p>
 */
public final class RtmartPublicKeyParser {

    private static final String PEM_BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String PEM_END = "-----END PUBLIC KEY-----";
    private static final Pattern COMPLETE_PEM_PATTERN = Pattern.compile(
            "\\A" + PEM_BEGIN + "\\s+([A-Za-z0-9+/=\\s]+?)\\s+" + PEM_END + "\\s*\\z");

    private RtmartPublicKeyParser() {
    }

    /**
     * 将 X.509 PEM 或裸 Base64 公钥转换为 RSA 公钥。
     *
     * @param configuredPublicKey 验签公钥配置，只能在本方法内存中短暂处理
     * @return 可用于 {@code SHA1withRSA} 验签的 RSA 公钥
     * @throws IllegalStateException 配置为空、Base64 非法或不是 RSA X.509 公钥时抛出
     */
    public static PublicKey parseX509(String configuredPublicKey) {
        if (configuredPublicKey == null || configuredPublicKey.isBlank()) {
            throw new IllegalStateException("rtmart inbound publicKey must not be blank");
        }
        try {
            String normalized = normalizeX509Base64(configuredPublicKey);
            byte[] encodedKey = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encodedKey));
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new IllegalStateException("rtmart inbound publicKey is invalid", exception);
        }
    }

    /**
     * 严格区分唯一、完整的 X.509 PEM 包装与不含任何 PEM 边界的裸 Base64。
     *
     * <p>禁止通过全局 replace “修复”片段、重复或混合边界，以免错误配置悄然被解释为另一把密钥。</p>
     */
    private static String normalizeX509Base64(String configuredPublicKey) {
        String trimmed = configuredPublicKey.trim();
        if (trimmed.contains("-----BEGIN") || trimmed.contains("-----END")) {
            Matcher matcher = COMPLETE_PEM_PATTERN.matcher(trimmed);
            if (!matcher.matches()) {
                throw new IllegalStateException("rtmart inbound publicKey encoding is invalid");
            }
            return matcher.group(1).replaceAll("\\s+", "");
        }
        return trimmed.replaceAll("\\s+", "");
    }
}
