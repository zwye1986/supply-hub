package com.aifuli.framework.apilog.core.util;

import cn.hutool.core.util.StrUtil;

/**
 * API 访问日志敏感路径判断工具。
 */
public final class ApiAccessLogPathUtils {

    private static final String[] SENSITIVE_PLATFORM_PATH_PREFIXES = {
            "/supply-chain/recharge-frequency/",
            "/admin-api/supply-chain/recharge-frequency/",
            "/trade/order/wusheng/",
            "/app-api/trade/order/wusheng/",
            "/open/mall/v1/",
            "/app-api/open/mall/v1/",
            "/rpc-api/member/platform-point/",
            "/rpc-api/supply-chain/instore/channel-command/",
            "/rpc-api/supply-chain/instore/channel-member/",
            "/rpc-api/supply-chain/instore/buyer-entry/",
            "/rpc-api/supply-chain/buyer/instore/payment/"
    };

    private ApiAccessLogPathUtils() {
    }

    /**
     * 判断请求路径是否可能携带短信验证码、平台签名、项目应用凭据或受保护业务正文。
     *
     * <p>供应链项目应用认证会在 Filter 读取正文后继续进入通用访问日志拦截器，因此四组受保护路径必须
     * 在此整体省略参数；不能仅依赖访问日志 Filter 的开关。设计依据：
     * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#44-失败语义}。</p>
     *
     * @param requestUri 请求路径
     * @return 是否禁止记录请求参数
     */
    public static boolean isSensitivePlatformPath(String requestUri) {
        return isSensitivePlatformPath(requestUri, "");
    }

    /**
     * 判断带部署上下文的请求路径是否可能携带敏感字段。
     *
     * <p>{@link jakarta.servlet.http.HttpServletRequest#getRequestURI()} 包含 context path，只有当
     * URI 确实以完整 context path 路径段开头时才允许剥离，避免把 URI 中段或相似字符串误当成
     * 部署上下文。设计依据：
     * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#44-失败语义}。</p>
     *
     * @param requestUri 请求 URI
     * @param contextPath Servlet 部署上下文；根上下文为空字符串
     * @return 是否禁止记录请求参数
     */
    public static boolean isSensitivePlatformPath(String requestUri, String contextPath) {
        if (StrUtil.isBlank(requestUri)) {
            return false;
        }
        String path = stripLeadingContextPath(requestUri, contextPath);
        for (String prefix : SENSITIVE_PLATFORM_PATH_PREFIXES) {
            if (StrUtil.startWith(path, prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 内部限频管理正文与异常原因均可能包含手机号，见
     * {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。 */
    public static boolean isRechargeFrequencyAdminPath(String requestUri, String contextPath) {
        if (requestUri == null) { return false; }
        String path = stripLeadingContextPath(requestUri, contextPath);
        return path.startsWith("/supply-chain/recharge-frequency/")
                || path.startsWith("/admin-api/supply-chain/recharge-frequency/");
    }

    private static String stripLeadingContextPath(String requestUri, String contextPath) {
        if (StrUtil.isBlank(contextPath) || "/".equals(contextPath)) {
            return requestUri;
        }
        if (requestUri.equals(contextPath)) {
            return "";
        }
        String contextPathWithSeparator = contextPath + "/";
        if (requestUri.startsWith(contextPathWithSeparator)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

}
