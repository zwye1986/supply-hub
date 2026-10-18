package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 仁跃HTTP回调的封闭拒绝原因；固定安全信息，不携供应商正文、目标或底层异常消息。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#513-临时本地认证与http闭环合同2026-10-18用户批准。
 */
public final class SingleStepCallbackException extends IllegalArgumentException {
    public enum Code {
        INVALID_CALLBACK("回调格式或参数无效"), CALLBACK_NOT_FOUND("回调原单不存在"),
        AUTHENTICATION_FAILED("回调认证失败"), IDENTITY_CONFLICT("回调原单身份不符"),
        CALLBACK_PROCESSING_FAILED("回调处理失败，请稍后重试");
        private final String message;
        Code(String message) { this.message = message; }
        public String message() { return message; }
    }
    private final Code code;
    public SingleStepCallbackException(Code code) { super(code.message()); this.code = code; }
    public Code code() { return code; }
}
