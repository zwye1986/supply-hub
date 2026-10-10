package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇回调时间戳超出窗口或格式与数据语义校验异常。
 * 对应 HTTP 400 状态码。
 */
public class HuashengCallbackValidationException extends RuntimeException {
    public HuashengCallbackValidationException(String message) {
        super(message);
    }

    public HuashengCallbackValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
