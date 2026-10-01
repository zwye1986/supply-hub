package com.aifuli.module.supplychain.api.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目应用统一认证签名客户端：按固定 9 字段签名串生成 HMAC-SHA256 认证头。
 *
 * <p>签名串字段顺序与空值语义、requestSha256 取原始 body 字节、Base64URL 编码，
 * 均与 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#4-签名协议}
 * 逐字段对齐；服务端验签是本工具的逆运算，两侧共用 {@link ProjectAuthOperations} 注册表。</p>
 */
public final class ProjectAppAuthSigner {

    public static final String HEADER_APP_ID = "appId";
    public static final String HEADER_KEY_VERSION = "keyVersion";
    public static final String HEADER_CONTRACT_VERSION = "contractVersion";
    public static final String HEADER_OPERATION_CODE = "operationCode";
    public static final String HEADER_TIMESTAMP = "timestamp";
    public static final String HEADER_NONCE = "nonce";
    public static final String HEADER_REQUEST_SHA256 = "requestSha256";
    public static final String HEADER_SIGNATURE = "signature";
    public static final String HEADER_BUYER_USER_ID = "buyerUserId";
    public static final String HEADER_CHANNEL_CODE = "channelCode";

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ProjectAppAuthSigner() {
    }

    /**
     * 为受保护操作生成认证头（INSTORE 十个、标准商品八个）（自动生成 timestamp 与 nonce）。
     *
     * @param credentials 调用方凭证
     * @param httpMethod  HTTP 方法
     * @param path        请求路径，不含 query
     * @param body        请求 body 原始字节，空 body 传 null 或空数组
     * @return 由操作能力决定的认证头
     */
    public static Map<String, String> sign(ProjectAppAuthCredentials credentials, String httpMethod,
                                           String path, byte[] body) {
        return sign(credentials, httpMethod, path, body, System.currentTimeMillis(), randomNonce());
    }

    /**
     * 为受保护操作生成认证头（INSTORE 十个、标准商品八个）；timestamp 与 nonce 由调用方显式提供（重放测试与固定场景用）。
     */
    public static Map<String, String> sign(ProjectAppAuthCredentials credentials, String httpMethod,
                                           String path, byte[] body, long timestamp, String nonce) {
        ProjectAuthOperations.Operation operation = ProjectAuthOperations.findByMethodAndPath(httpMethod, path)
                .orElseThrow(() -> new IllegalArgumentException("未登记的受保护操作: " + httpMethod + " " + path));
        validateScope(operation, credentials);
        String requestSha256 = sha256Hex(body);
        String signature = hmacSha256Base64Url(credentials.appKey(), canonicalString(
                credentials.appId(), String.valueOf(credentials.keyVersion()),
                operation.contractVersion(), operation.operationCode(), String.valueOf(timestamp),
                nonce, requestSha256, nullToEmpty(credentials.buyerUserId()),
                nullToEmpty(credentials.channelCode())));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_APP_ID, credentials.appId());
        headers.put(HEADER_KEY_VERSION, String.valueOf(credentials.keyVersion()));
        headers.put(HEADER_CONTRACT_VERSION, operation.contractVersion());
        headers.put(HEADER_OPERATION_CODE, operation.operationCode());
        headers.put(HEADER_TIMESTAMP, String.valueOf(timestamp));
        headers.put(HEADER_NONCE, nonce);
        headers.put(HEADER_REQUEST_SHA256, requestSha256);
        headers.put(HEADER_SIGNATURE, signature);
        // 标准商品不传用户/渠道身份头；九字段签名串末两项仍为空，INSTORE 输出保持十头。
        // 详见 docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md#21-认证实施边界。
        if (!ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT.equals(operation.requiredCapability())) {
            headers.put(HEADER_BUYER_USER_ID, nullToEmpty(credentials.buyerUserId()));
            headers.put(HEADER_CHANNEL_CODE, nullToEmpty(credentials.channelCode()));
        }
        return headers;
    }

    /**
     * 构造固定 9 字段签名串；不适用的 scope 字段取空字符串。服务端验签共用本方法保证逆运算一致。
     *
     * <p>不变量：各字段值不得含换行符（\n、\r）。签名串以换行符做字段分隔，字段值携带换行会
     * 造成字段边界歧义（CR/LF 同时被 HTTP header 值禁止），此处为纵深防御约定，由调用方保证。</p>
     */
    public static String canonicalString(String appId, String keyVersion, String contractVersion,
                                         String operationCode, String timestamp, String nonce,
                                         String requestSha256, String buyerUserId, String channelCode) {
        return String.join("\n", appId, keyVersion, contractVersion, operationCode,
                timestamp, nonce, requestSha256, buyerUserId, channelCode);
    }

    /** 计算请求 body 原始字节的 SHA-256 小写十六进制摘要；空 body 取空字节串摘要。 */
    public static String sha256Hex(byte[] body) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(body == null ? new byte[0] : body);
            char[] chars = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                chars[i * 2] = HEX[(hash[i] >> 4) & 0xF];
                chars[i * 2 + 1] = HEX[hash[i] & 0xF];
            }
            return new String(chars);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 缺少 SHA-256 算法", ex);
        }
    }

    /** 以 appKey 为密钥计算 HMAC-SHA256 并 Base64URL 编码（无填充）。 */
    public static String hmacSha256Base64Url(String appKey, String canonicalString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 缺少 HmacSHA256 算法", ex);
        } catch (InvalidKeyException ex) {
            throw new IllegalStateException("appKey 初始化 HMAC 密钥失败", ex);
        }
    }

    /** 生成 16 字节 Base64URL 随机 nonce。 */
    public static String randomNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 客户端侧 scope 快速失败：必填 scope 缺失、非必填 scope 字段未置空或 NONE 操作携带 scope 直接拒绝，
     * 与设计 §6.1 第 8 步「必填组非空；非必填组必须为空」对称，避免到服务端才报模糊的认证失败。
     */
    private static void validateScope(ProjectAuthOperations.Operation operation,
                                      ProjectAppAuthCredentials credentials) {
        switch (operation.scopeType()) {
            case CHANNEL_CODE -> {
                if (credentials.channelCode() == null || credentials.channelCode().isBlank()) {
                    throw new IllegalArgumentException(
                            "操作 " + operation.operationCode() + " 的凭证缺少必填 scope 字段 channelCode");
                }
                if (credentials.buyerUserId() != null) {
                    throw new IllegalArgumentException(
                            "操作 " + operation.operationCode() + " 非必填 scope 字段 buyerUserId 必须为空");
                }
            }
            case BUYER_USER_ID -> {
                if (credentials.buyerUserId() == null || credentials.buyerUserId().isBlank()) {
                    throw new IllegalArgumentException(
                            "操作 " + operation.operationCode() + " 的凭证缺少必填 scope 字段 buyerUserId");
                }
                if (credentials.channelCode() != null) {
                    throw new IllegalArgumentException(
                            "操作 " + operation.operationCode() + " 非必填 scope 字段 channelCode 必须为空");
                }
            }
            case NONE -> {
                if (credentials.buyerUserId() != null || credentials.channelCode() != null) {
                    throw new IllegalArgumentException(
                            "操作 " + operation.operationCode() + " 不允许携带 scope 声明");
                }
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
