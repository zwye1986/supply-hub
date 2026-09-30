package com.aifuli.module.supplychain.integration.rtmart;

import java.util.regex.Pattern;

/**
 * 旅通入口适配器的受控失败语义。
 *
 * <p>该异常仅保存可安全诊断的失败分类、旅通业务码和 HTTP 状态；不得在 message、字段或 cause 包装时
 * 拼接 appKey、私钥、token、sign、完整请求参数集、响应正文或跳转地址。设计见
 * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#5-响应与错误语义}。</p>
 */
public final class RtmartEntryException extends RuntimeException {

    private static final String UNKNOWN_REMOTE_CODE = "UNKNOWN";
    private static final Pattern SAFE_REMOTE_CODE_PATTERN = Pattern.compile("E\\d{4}");

    /** 适配器对上层暴露的稳定失败分类。 */
    public enum Reason {
        /** 配置缺失、不合法或私钥不可用。 */
        CONFIGURATION,
        /** nonce 或签名计算失败。 */
        SIGNATURE,
        /** DNS、连接、TLS 或读写失败。 */
        TRANSPORT,
        /** 第三方返回非 2xx HTTP 状态。 */
        HTTP_STATUS,
        /** 第三方返回业务失败码。 */
        REMOTE_ERROR,
        /** 响应体或协议字段不符合约定。 */
        PROTOCOL
    }

    private final Reason reason;
    private final String remoteCode;
    private final Integer httpStatus;

    private RtmartEntryException(Reason reason, String message, String remoteCode,
                                 Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.remoteCode = normalizeRemoteCode(remoteCode);
        this.httpStatus = httpStatus;
    }

    /** @return 安全的内部失败分类。 */
    public Reason getReason() {
        return reason;
    }

    /** @return 可安全记录的旅通业务码；未收到时为 {@code null}。 */
    public String getRemoteCode() {
        return remoteCode;
    }

    /** @return 可安全记录的 HTTP 状态；未收到时为 {@code null}。 */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** 创建不包含配置原文的配置失败。 */
    static RtmartEntryException configuration() {
        return configuration(null);
    }

    /**
     * 创建不包含配置原文的配置失败，根因统一转换为不保留原 message/对象的安全副本。
     */
    static RtmartEntryException configuration(Throwable cause) {
        return new RtmartEntryException(Reason.CONFIGURATION, "Rtmart configuration is invalid", null, null,
                RtmartSafeCause.from("configuration", cause));
    }

    /** 创建不包含签名原文或秘密的签名失败。 */
    static RtmartEntryException signature(Throwable cause) {
        return new RtmartEntryException(Reason.SIGNATURE, "Rtmart request signing failed", null, null,
                RtmartSafeCause.from("signature", cause));
    }

    /** 创建不包含请求或响应正文的传输失败。 */
    static RtmartEntryException transport(Throwable cause) {
        return new RtmartEntryException(Reason.TRANSPORT, "Rtmart request transport failed", null, null,
                RtmartSafeCause.from("transport", cause));
    }

    /** 创建不包含响应正文的 HTTP 状态失败。 */
    static RtmartEntryException httpStatus(int httpStatus) {
        return new RtmartEntryException(Reason.HTTP_STATUS, "Rtmart returned a non-success HTTP status",
                null, httpStatus, null);
    }

    /** 创建不包含响应正文的远端业务失败。 */
    static RtmartEntryException remoteError(String remoteCode) {
        return new RtmartEntryException(Reason.REMOTE_ERROR, "Rtmart returned a remote error",
                remoteCode, null, null);
    }

    /** 创建不包含响应正文的协议失败。 */
    static RtmartEntryException protocol(Throwable cause) {
        return new RtmartEntryException(Reason.PROTOCOL, "Rtmart response protocol is invalid", null, null,
                RtmartSafeCause.from("protocol", cause));
    }

    /**
     * 将第三方业务码限制为旅通正式的 {@code E####} 形态。
     *
     * <p>不得截断或转义非法值，避免 CRLF、凭证片段或伪装 appKey 在日志中以另一种形式残留；所有非法值
     * 统一降级为 {@value #UNKNOWN_REMOTE_CODE}。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#5-响应与错误语义}。</p>
     */
    private static String normalizeRemoteCode(String remoteCode) {
        if (remoteCode == null) {
            return null;
        }
        return SAFE_REMOTE_CODE_PATTERN.matcher(remoteCode).matches() ? remoteCode : UNKNOWN_REMOTE_CODE;
    }
}
