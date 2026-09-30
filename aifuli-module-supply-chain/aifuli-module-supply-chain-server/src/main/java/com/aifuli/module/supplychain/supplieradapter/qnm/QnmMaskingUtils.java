package com.aifuli.module.supplychain.supplieradapter.qnm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 奇那梦适配器日志脱敏与传输防风暴工具。
 *
 * <p>遵守 AGENTS.md 第 6 条“第三方调用须打详细日志：敏感字段（密钥、完整凭证、手机号等）脱敏”。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
public final class QnmMaskingUtils {

    private static final String MASK_FOUR = "****";
    private static final int MAX_LOG_BODY_CHARS = 512;
    private static final Pattern SENSITIVE_JSON_ARRAY = Pattern.compile(
            "(?i)(\"(?:cards|cardList|card_list)\"\\s*:\\s*)\\[(?:[^\\[\\]]|\\[[^\\[\\]]*])*\\]");
    private static final Pattern SENSITIVE_JSON_STRING = Pattern.compile(
            "(?i)(\"(?:cardNo|card_no|cardno|cardNumber|card_number|cardPwd|card_pwd|cardpwd|"
                    + "cardSecret|card_secret|password|pwd|secret|key|secretKey|secret_key|"
                    + "appSecret|app_secret|token|account|phone|mobile|sign)\"\\s*:\\s*)"
                    + "\"(?:\\\\.|[^\"\\\\])*\"");

    private QnmMaskingUtils() {
    }

    /**
     * 充值账号/手机号脱敏：保留前 3 后 4 位，其余掩码；短于 7 位时全部掩码。
     */
    public static String maskAccount(String account) {
        if (account == null || account.isBlank()) {
            return "";
        }
        String trimmed = account.trim();
        int len = trimmed.length();
        if (len <= 7) {
            return MASK_FOUR;
        }
        return trimmed.substring(0, 3) + MASK_FOUR + trimmed.substring(len - 4);
    }

    /**
     * 卡密脱敏：禁止明文输出到日志，固定返回掩码。
     */
    public static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        return MASK_FOUR;
    }

    /**
     * 卡号脱敏：保留前 4 后 4 位。
     */
    public static String maskCardNo(String cardNo) {
        if (cardNo == null || cardNo.isBlank()) {
            return "";
        }
        String trimmed = cardNo.trim();
        int len = trimmed.length();
        if (len <= 8) {
            return MASK_FOUR;
        }
        return trimmed.substring(0, 4) + MASK_FOUR + trimmed.substring(len - 4);
    }

    /**
     * 请求参数脱敏快照（供日志打印使用，屏蔽密钥与敏感账号）。
     */
    public static Map<String, String> maskParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue();
            if (v == null) {
                continue;
            }
            if (isAccountField(k)) {
                masked.put(k, maskAccount(v));
            } else if (isCardNoField(k)) {
                masked.put(k, maskCardNo(v));
            } else if (QnmEndpointConstants.PARAM_SIGN.equalsIgnoreCase(k)) {
                masked.put(k, v.length() > 8 ? v.substring(0, 4) + MASK_FOUR + v.substring(v.length() - 4) : MASK_FOUR);
            } else if (isSecretOrPasswordField(k)) {
                masked.put(k, MASK_FOUR);
            } else {
                masked.put(k, v);
            }
        }
        return Collections.unmodifiableMap(masked);
    }

    /**
     * 响应体日志脱敏：屏蔽卡密/卡号/账号等 JSON 字段，并截断以防日志风暴。
     */
    public static String maskResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String masked = SENSITIVE_JSON_ARRAY.matcher(body).replaceAll("$1\"" + MASK_FOUR + "\"");
        masked = SENSITIVE_JSON_STRING.matcher(masked).replaceAll("$1\"" + MASK_FOUR + "\"");
        if (masked.length() > MAX_LOG_BODY_CHARS) {
            return masked.substring(0, MAX_LOG_BODY_CHARS) + "...(truncated,len=" + body.length() + ")";
        }
        return masked;
    }

    private static boolean isAccountField(String k) {
        return QnmEndpointConstants.PARAM_ACCOUNT.equalsIgnoreCase(k)
                || "rechargeAccount".equalsIgnoreCase(k)
                || "recharge_account".equalsIgnoreCase(k)
                || "phone".equalsIgnoreCase(k)
                || "mobile".equalsIgnoreCase(k);
    }

    private static boolean isCardNoField(String k) {
        return "cardNo".equalsIgnoreCase(k)
                || "card_no".equalsIgnoreCase(k)
                || "cardno".equalsIgnoreCase(k)
                || "cardNumber".equalsIgnoreCase(k)
                || "card_number".equalsIgnoreCase(k);
    }

    private static boolean isSecretOrPasswordField(String k) {
        return QnmEndpointConstants.FIELD_CARDS.equalsIgnoreCase(k)
                || "cardList".equalsIgnoreCase(k)
                || "card_list".equalsIgnoreCase(k)
                || "cardPwd".equalsIgnoreCase(k)
                || "card_pwd".equalsIgnoreCase(k)
                || "cardpwd".equalsIgnoreCase(k)
                || "cardSecret".equalsIgnoreCase(k)
                || "card_secret".equalsIgnoreCase(k)
                || "password".equalsIgnoreCase(k)
                || "pwd".equalsIgnoreCase(k)
                || "secret".equalsIgnoreCase(k)
                || "key".equalsIgnoreCase(k)
                || "secretKey".equalsIgnoreCase(k)
                || "secret_key".equalsIgnoreCase(k)
                || "appSecret".equalsIgnoreCase(k)
                || "app_secret".equalsIgnoreCase(k)
                || "token".equalsIgnoreCase(k);
    }
}
