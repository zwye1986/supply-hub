package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

/**
 * 华昇 66 安全校验拒绝的受控安全原因分类。
 *
 * <p>职责：仅对 66 安全校验拒绝的官方固定提示做封闭分类，供错误分类决策区分
 * “前置安全配置拒绝”（可直接失败）与“请求过期／重复 nonce／未知提示”（保守原单核实）。
 *
 * <p>不变量：分类只匹配官方固定提示关键词，不持久化任意原始 message；
 * 未匹配任何官方提示时分类结果为空（UNKNOWN 语义），决策层不得据此猜测副作用。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#43-61-与-66、#82-日志与诊断。
 */
public enum HuashengSecurityReason {
    /** 缺少必填字段。 */
    MISSING_FIELD,
    /** 签名校验失败。 */
    SIGNATURE_INVALID,
    /** 解密失败。 */
    DECRYPT_FAILED,
    /** 密钥配置错误。 */
    KEY_CONFIG_ERROR,
    /** 应用被禁用。 */
    APP_DISABLED,
    /** 请求时间戳过期。 */
    REQUEST_EXPIRED,
    /** 重复 nonce。 */
    DUPLICATE_NONCE;

    /**
     * 是否为前置安全配置拒绝（缺字段、签名、解密、密钥配置、应用禁用）。
     * 前置安全配置拒绝说明请求在进入订单业务处理前被安全层拒绝；
     * 请求过期与重复 nonce 不能证明业务未执行，不属此类。
     */
    public boolean isPreSecurityRejection() {
        return this == MISSING_FIELD || this == SIGNATURE_INVALID || this == DECRYPT_FAILED
                || this == KEY_CONFIG_ERROR || this == APP_DISABLED;
    }

    /**
     * 从官方固定提示文本匹配受控安全原因。
     *
     * <p>不变量：仅接受无控制字符、不超过 256 字符的文本；仅匹配官方固定提示关键词；
     * 未匹配返回 {@code null}，绝不按自由文本猜测。
     *
     * @param message 供应商返回的 message 文本
     * @return 匹配到的受控安全原因；未匹配返回 null
     */
    public static HuashengSecurityReason fromOfficialMessage(String message) {
        if (message == null || message.isEmpty() || message.length() > 256) {
            return null;
        }
        for (int i = 0; i < message.length(); i++) {
            if (Character.isISOControl(message.charAt(i))) {
                return null;
            }
        }
        // 用户提供的官方安全中间件固定提示；严格全串匹配，任意自由文本不得构成资金证明。
        return switch (message) {
            case "缺少Appid", "缺少签名", "缺少timestamp", "缺少nonce", "timestamp格式错误" -> MISSING_FIELD;
            case "Appid无效", "RSA公钥未配置", "RSA公钥解析失败", "AES密钥未配置" -> KEY_CONFIG_ERROR;
            case "签名格式错误", "签名验证失败" -> SIGNATURE_INVALID;
            case "数据解密失败" -> DECRYPT_FAILED;
            case "经销商已禁用" -> APP_DISABLED;
            case "请求已过期" -> REQUEST_EXPIRED;
            case "重复请求" -> DUPLICATE_NONCE;
            default -> null;
        };
    }
}
