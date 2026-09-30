package com.aifuli.framework.jdvop.core.exception;

/**
 * 京东网关明确拒绝请求的异常。
 *
 * <p>签名、Token、权限或限流等网关错误表示业务请求未被受理，可使用原业务号退避重试。</p>
 */
public class JdVopGatewayException extends JdVopException {

    public JdVopGatewayException(String code, String message) {
        super(code, message);
    }

}
