package com.aifuli.module.supplychain.integration.rtmart;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 旅通 JSON 参数签名器，封装 app_key、nonce、时间戳和 RSA 签名细节。
 *
 * <p>输入 Map 不会被修改，输出也不可变；签名原文仅在当前调用栈中短暂存在，禁止记录到日志或异常中。
 * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#43-签名规则}。</p>
 */
final class RtmartRequestSigner {

    private static final String APP_KEY_FIELD = "app_key";
    private static final String NONCE_FIELD = "nonce_str";
    private static final String TIMESTAMP_FIELD = "timestamp";
    private static final String SIGN_FIELD = "sign";
    private static final int NONCE_BYTE_LENGTH = 16;
    private static final String NONCE_PATTERN = "[0-9a-f]{32}";

    private final String appKey;
    private final PrivateKey privateKey;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;

    /**
     * 创建可测试的签名器；Clock 和 nonceSupplier 由调用方注入以保证签名可重复验证。
     *
     * <p>appKey/私钥只作为内存内签名输入，不进入诊断文本。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#43-签名规则}。</p>
     */
    RtmartRequestSigner(String appKey, PrivateKey privateKey, Clock clock, Supplier<String> nonceSupplier) {
        if (appKey == null || appKey.isBlank()) {
            throw RtmartEntryException.configuration();
        }
        if (privateKey == null) {
            throw RtmartEntryException.configuration();
        }
        this.appKey = appKey;
        this.privateKey = privateKey;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nonceSupplier = Objects.requireNonNull(nonceSupplier, "nonceSupplier");
    }

    /**
     * 创建生产签名器：每次签名使用 SecureRandom 生成 16 字节、32 位小写十六进制 nonce。
     *
     * <p>时间戳始终来自注入 Clock，不在调用处临时改用系统时钟。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#42-请求字段}。</p>
     */
    static RtmartRequestSigner production(String appKey, PrivateKey privateKey, Clock clock) {
        SecureRandom secureRandom = new SecureRandom();
        return new RtmartRequestSigner(appKey, privateKey, clock, () -> {
            byte[] nonceBytes = new byte[NONCE_BYTE_LENGTH];
            secureRandom.nextBytes(nonceBytes);
            return HexFormat.of().formatHex(nonceBytes);
        });
    }

    /**
     * 过滤 null 参数并生成旅通约定的不可变签名参数集。
     *
     * <p>已有 sign 会被忽略；app_key、nonce_str、timestamp 由本签名器覆盖，避免调用方意外复用旧值。
     * 空字符串参数保留参与签名，与旅通 {@code signData} 中 {@code mapKeySort} 仅过滤 null 的行为一致。</p>
     *
     * <p>字段按名称自然升序拼接，生成的不可变 Map 同时是唯一可被 JSON 编码的参数集。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#43-签名规则}。</p>
     *
     * @param requestFields 渠道业务字段；该 Map 不会被修改
     * @return 已附加 app_key、nonce_str、timestamp、sign 的不可变 Map
     */
    public Map<String, String> sign(Map<String, String> requestFields) {
        Objects.requireNonNull(requestFields, "requestFields");
        String nonce = nextNonce();
        TreeMap<String, String> fields = new TreeMap<>();
        requestFields.forEach((key, value) -> {
            if (!SIGN_FIELD.equals(key) && value != null) {
                fields.put(key, value);
            }
        });
        fields.put(APP_KEY_FIELD, appKey);
        fields.put(NONCE_FIELD, nonce);
        fields.put(TIMESTAMP_FIELD, Long.toString(clock.instant().getEpochSecond()));

        String canonicalText = fields.entrySet().stream()
                .map(entry -> entry.getKey() + entry.getValue())
                .reduce("", String::concat);
        fields.put(SIGN_FIELD, createSignature(canonicalText));
        return Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /**
     * 获取并严格校验单次 nonce；supplier 异常经统一安全 cause 边界保留类型/栈帧，不保留原 message。
     * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#52-失败分类}。
     */
    private String nextNonce() {
        try {
            String nonce = nonceSupplier.get();
            if (nonce == null || !nonce.matches(NONCE_PATTERN)) {
                throw RtmartEntryException.signature(null);
            }
            return nonce;
        } catch (RtmartEntryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw RtmartEntryException.signature(exception);
        }
    }

    /**
     * 对 UTF-8 签名原文执行 {@code SHA1withRSA} 并返回标准 Base64；原文不进入异常或日志。
     * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#43-签名规则}。
     */
    private String createSignature(String canonicalText) {
        try {
            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(privateKey);
            signer.update(canonicalText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException exception) {
            throw RtmartEntryException.signature(exception);
        }
    }
}
