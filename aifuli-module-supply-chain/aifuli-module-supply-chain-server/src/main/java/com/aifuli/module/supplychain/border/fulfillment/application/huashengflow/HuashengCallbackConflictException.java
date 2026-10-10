package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇回调 nonce 冲突或改投版本异常。
 * 对应 HTTP 409 状态码。
 */
public class HuashengCallbackConflictException extends RuntimeException {
    public HuashengCallbackConflictException(String message) {
        super(message);
    }

    public HuashengCallbackConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
