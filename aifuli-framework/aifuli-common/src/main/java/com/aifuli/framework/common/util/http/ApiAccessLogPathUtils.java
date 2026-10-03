package com.aifuli.framework.common.util.http;

import cn.hutool.core.util.StrUtil;

/**
 * API 访问日志敏感路径判断工具。
 *
 * <p>本工具被 Servlet 技术栈（aifuli-spring-boot-starter-web）与 Reactive 技术栈（aifuli-gateway）
 * 共同使用，因此下沉到 aifuli-common；不得引入 Servlet/Reactive 类型依赖。
 * 路径分类必须与供应链 {@code ProjectAuthOperations} 登记的开放路径一致，修改时双侧同步。
 * 设计见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#4-共享web保护与影响范围}。</p>
 */
public final class ApiAccessLogPathUtils {

    /**
     * 标准订单开放根路径。与供应链侧 {@code ProjectAuthOperations.STANDARD_ORDERS_PATH} 保持一致；
     * common 模块不能依赖供应链模块，故此处以常量固化，修改时必须双侧同步。
     */
    public static final String STANDARD_ORDERS_PATH = "/open-api/supply-chain/project/v1/orders";

    private static final String STANDARD_ORDERS_PATH_SLASH = STANDARD_ORDERS_PATH + "/";

    /** 限频管理路径前缀（无 admin-api 前缀）；供敏感平台前缀数组与限频判断方法共用，避免字面量漂移。 */
    private static final String RECHARGE_FREQUENCY_PATH = "/supply-chain/recharge-frequency/";

    /** 限频管理 admin-api 路径前缀；供敏感平台前缀数组与限频判断方法共用，避免字面量漂移。 */
    private static final String RECHARGE_FREQUENCY_ADMIN_PATH = "/admin-api/supply-chain/recharge-frequency/";

    /**
     * 平台访问日志需整体省略请求参数的前缀集合。
     *
     * <p>标准订单根路径不在此数组内用 {@code startWith} 匹配，避免 {@code orders-other} 等相似前缀被误判；
     * 由 {@link #isSensitivePlatformPath(String, String)} 内部委托 {@link #matchesStandardOrdersPath} 精确判断，
     * 与 {@link #isStandardOrdersPath} 保持同一口径。设计见
     * {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#4-共享web保护与影响范围}。</p>
     */
    private static final String[] SENSITIVE_PLATFORM_PATH_PREFIXES = {
            RECHARGE_FREQUENCY_PATH,
            RECHARGE_FREQUENCY_ADMIN_PATH,
            "/trade/order/wusheng/",
            "/app-api/trade/order/wusheng/",
            "/open/mall/v1/",
            "/app-api/open/mall/v1/",
            "/rpc-api/member/platform-point/",
            "/rpc-api/supply-chain/instore/channel-command/",
            "/rpc-api/supply-chain/instore/channel-member/",
            "/rpc-api/supply-chain/instore/buyer-entry/",
            "/rpc-api/supply-chain/buyer/instore/payment/",
    };

    /**
     * 凭据写入含秘密明文或环境引用，保护精确根及其子路径，兼容管理端前缀与部署 context path。
     * 不覆盖 credential-items-other 等相似路径。设计见
     * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#151-凭据写入日志保护。
     */
    private static final String[] SUPPLIER_CREDENTIAL_WRITE_PATHS = {
            "/supply-chain/supplier-config/credential-items",
            "/admin-api/supply-chain/supplier-config/credential-items",
            "/supply-chain/supplier-config/credential-slots",
            "/admin-api/supply-chain/supplier-config/credential-slots"
    };

    public static boolean isSupplierCredentialWritePath(String requestUri, String contextPath) {
        if (requestUri == null) { return false; }
        String path = stripLeadingContextPath(requestUri, contextPath);
        for (String root : SUPPLIER_CREDENTIAL_WRITE_PATHS) {
            if (path.equals(root) || path.startsWith(root + "/")) { return true; }
        }
        return false;
    }

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
     * <p>请求 URI 包含 context path，只有当 URI 确实以完整 context path 路径段开头时才允许剥离，
     * 避免把 URI 中段或相似字符串误当成部署上下文。设计依据：
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
        // 标准订单路径精确保留根与子路径，排除 orders-other 等相似前缀
        if (matchesStandardOrdersPath(path) || isSupplierCredentialWritePath(requestUri, contextPath)) {
            return true;
        }
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
        return matchesRechargeFrequencyAdminPath(stripLeadingContextPath(requestUri, contextPath));
    }

    /**
     * 判断请求路径是否需要"安全异常处理"（限频管理、标准订单与供应商凭据写入路径）。
     *
     * <p>供 Servlet 栈访问日志 Filter 与全局异常处理器共用同一份判断，避免各自私有组装
     * {@code ||} 导致规则漂移。设计见
     * {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#4-共享web保护与影响范围}。</p>
     *
     * @param requestUri 请求 URI
     * @param contextPath Servlet 部署上下文；根上下文为空字符串
     * @return true 表示该请求路径需走安全异常处理收敛策略
     */
    public static boolean isSensitiveProtectionPath(String requestUri, String contextPath) {
        if (requestUri == null) { return false; }
        String path = stripLeadingContextPath(requestUri, contextPath);
        return matchesStandardOrdersPath(path) || matchesRechargeFrequencyAdminPath(path)
                || isSupplierCredentialWritePath(requestUri, contextPath);
    }

    /** 标准订单开放路径：包含原始签名正文、手机号与卡券资产，严禁日志记录正文与响应。
     * 设计见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#4-共享web保护与影响范围}。 */
    public static boolean isStandardOrdersPath(String requestUri, String contextPath) {
        if (requestUri == null) { return false; }
        return matchesStandardOrdersPath(stripLeadingContextPath(requestUri, contextPath));
    }

    /** 标准订单安全URL：避免在未知子路径中泄漏夹带的参数或账号。 */
    public static String safeStandardOrderUrl(String requestUri, String contextPath) {
        if (requestUri == null) { return STANDARD_ORDERS_PATH; }
        String path = stripLeadingContextPath(requestUri, contextPath);
        if (STANDARD_ORDERS_PATH.equals(path) || isRegisteredStandardOrderSubPath(path)) {
            return path;
        }
        return STANDARD_ORDERS_PATH + "/[protected]";
    }

    private static boolean isRegisteredStandardOrderSubPath(String path) {
        return path.equals(STANDARD_ORDERS_PATH + "/query")
                || path.equals(STANDARD_ORDERS_PATH + "/assets/query");
    }

    /** 已剥离 context path 后的标准订单路径精确匹配：根路径或带尾斜杠的子路径。 */
    private static boolean matchesStandardOrdersPath(String strippedPath) {
        return STANDARD_ORDERS_PATH.equals(strippedPath)
                || strippedPath.startsWith(STANDARD_ORDERS_PATH_SLASH);
    }

    /** 已剥离 context path 后的限频管理路径匹配：复用常量，避免字面量漂移。 */
    private static boolean matchesRechargeFrequencyAdminPath(String strippedPath) {
        return strippedPath.startsWith(RECHARGE_FREQUENCY_PATH)
                || strippedPath.startsWith(RECHARGE_FREQUENCY_ADMIN_PATH);
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
