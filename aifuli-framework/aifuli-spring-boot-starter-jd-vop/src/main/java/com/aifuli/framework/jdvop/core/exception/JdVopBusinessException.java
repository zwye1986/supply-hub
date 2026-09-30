package com.aifuli.framework.jdvop.core.exception;

/**
 * 京东 VOP 已明确拒绝业务请求的异常。
 *
 * <p>该异常表示京东已经返回明确业务失败，可与网络超时等结果不确定异常区分。</p>
 */
public class JdVopBusinessException extends JdVopException {

    public JdVopBusinessException(String code, String message) {
        super(code, message);
    }

}
