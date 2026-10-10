package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇回调未找到原单或版本配置异常。
 * 对应 HTTP 404 状态码。
 */
public class HuashengCallbackNotFoundException extends RuntimeException {
    public HuashengCallbackNotFoundException(String message) {
        super(message);
    }

    public HuashengCallbackNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
