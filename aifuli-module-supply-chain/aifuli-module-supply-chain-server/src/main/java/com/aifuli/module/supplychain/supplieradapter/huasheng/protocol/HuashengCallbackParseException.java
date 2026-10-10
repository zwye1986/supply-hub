package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

/**
 * 华昇回调协议解析与认证异常。
 *
 * <p>职责：当回调报文超过体积上限、Header 超过限制、验签失败、解密失败或 JSON 字段格式非法时抛出。
 * 不输出敏感密文、签名或密钥。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#81-冻结身份认证。
 */
public class HuashengCallbackParseException extends RuntimeException {

    public HuashengCallbackParseException(String message) {
        super(message);
    }

    public HuashengCallbackParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
