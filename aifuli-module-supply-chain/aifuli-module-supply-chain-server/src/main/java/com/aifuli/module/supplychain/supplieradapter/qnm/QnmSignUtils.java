package com.aifuli.module.supplychain.supplieradapter.qnm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 奇那梦（QNM）GameOrder V1 MD5 签名与验签工具。
 *
 * <p>规约说明：
 * <ol>
 *   <li>过滤 {@code sign}、{@code sign_type} 以及 null/空字符串参数；</li>
 *   <li>所有参与签名的键按 ASCII 字典升序（TreeMap 自然排序）；</li>
 *   <li>拼接待签字符串格式：{@code k1=v1&k2=v2...&key={secretKey}}；</li>
 *   <li>使用 UTF-8 编码计算 32 位小写 MD5 十六进制字符串；</li>
 *   <li>验签时进行忽略大小写的常量时间比较。</li>
 * </ol>
 * </p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#101-奇那梦qnmgameorder-v1-协议实现规约}。</p>
 */
public final class QnmSignUtils {

    private static final String SIGN_PARAM = "sign";
    private static final String SIGN_TYPE_PARAM = "sign_type";
    private static final String KEY_CONNECTOR = "&key=";

    private QnmSignUtils() {
    }

    /**
     * 生成待签名字段串（不包含 MD5 计算，便于排查与日志审计）。
     *
     * @param params    参数 Map
     * @param secretKey 签名密钥
     * @return 待签字符串
     */
    public static String buildSignString(Map<String, String> params, String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("QNM secretKey must not be blank for signing");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                if (key == null || val == null || val.isBlank()) {
                    continue;
                }
                String trimmedKey = key.trim();
                if (SIGN_PARAM.equalsIgnoreCase(trimmedKey) || SIGN_TYPE_PARAM.equalsIgnoreCase(trimmedKey)) {
                    continue;
                }
                sorted.put(trimmedKey, val.trim());
            }
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        if (!sorted.isEmpty()) {
            builder.append(KEY_CONNECTOR);
        } else {
            builder.append("key=");
        }
        builder.append(secretKey.trim());
        return builder.toString();
    }

    /**
     * 计算 QNM MD5 签名（32 位小写十六进制）。
     *
     * @param params    参数 Map
     * @param secretKey 供应商密钥
     * @return 签名字符串
     */
    public static String sign(Map<String, String> params, String secretKey) {
        String signContent = buildSignString(params, secretKey);
        return md5Hex(signContent);
    }

    /**
     * 校验 QNM 签名。
     *
     * @param params      原始参数 Map（可包含或不包含 sign）
     * @param secretKey   供应商密钥
     * @param requestSign 待验证的签名
     * @return true 若验签通过
     */
    public static boolean verify(Map<String, String> params, String secretKey, String requestSign) {
        if (params == null || params.isEmpty() || requestSign == null || requestSign.isBlank() || secretKey == null || secretKey.isBlank()) {
            return false;
        }
        // 至少包含一个有效业务字段才允许验签，防止空载荷通过伪造空签名绕过
        boolean hasBusinessParam = false;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            if (key == null || val == null || val.isBlank()) {
                continue;
            }
            String trimmedKey = key.trim();
            if (!SIGN_PARAM.equalsIgnoreCase(trimmedKey) && !SIGN_TYPE_PARAM.equalsIgnoreCase(trimmedKey)) {
                hasBusinessParam = true;
                break;
            }
        }
        if (!hasBusinessParam) {
            return false;
        }
        String calculated = sign(params, secretKey);
        return constantTimeEquals(calculated.toLowerCase(Locale.ROOT), requestSign.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * MD5 计算 32 位小写十六进制串。
     */
    public static String md5Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    /**
     * 常量时间字符串比较，防止时序攻击。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
