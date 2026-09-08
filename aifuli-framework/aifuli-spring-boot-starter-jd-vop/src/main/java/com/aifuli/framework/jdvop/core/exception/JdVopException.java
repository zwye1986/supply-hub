package com.aifuli.framework.jdvop.core.exception;

/**
 * 京东 VOP 调用异常。
 */
public class    JdVopException extends RuntimeException {

    private final String code;

    public JdVopException(String message) {
        this(null, message, null);
    }

    public JdVopException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public JdVopException(String code, String message) {
        this(code, message, null);
    }

    public JdVopException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 获取京东返回的错误码。
     *
     * @return 错误码；本地异常时可能为空
     */
    public String getCode() {
        return code;
    }

}
