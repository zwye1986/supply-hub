package com.aifuli.gateway.filter.logging;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aifuli.framework.common.util.http.ApiAccessLogPathUtils;
import com.aifuli.framework.common.util.json.JsonUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 网关访问日志脱敏工具。
 * <p>
 * 仅作用于日志副本，不得修改实际转发的请求体/响应体。
 */
public final class AccessLogSanitizeUtils {

    /** 开放商城接口路径前缀：禁止记录原始请求/响应体 */
    public static final String OPEN_MALL_PATH_PREFIX = "/app-api/open/mall/v1/";

    /** 脱敏占位符 */
    public static final String MASK = "***";

    /** 开放接口路径下 body 的日志占位说明 */
    public static final String BODY_OMITTED = "[omitted]";

    /**
     * 判断是否为标准订单接口路径。
     *
     * <p>标准订单路径分类统一由 common 层 {@link ApiAccessLogPathUtils} 提供，
     * 与 Servlet 技术栈共享同一份定义，避免双栈规则漂移。网关无 context path，传空串。</p>
     */
    public static boolean isStandardOrdersPath(String requestUrl) {
        return ApiAccessLogPathUtils.isStandardOrdersPath(requestUrl, "");
    }

    /**
     * 判断是否为华昇供应商回调路径。
     */
    public static boolean isHuashengCallbackPath(String requestUrl) {
        return ApiAccessLogPathUtils.isHuashengCallbackPath(requestUrl, "");
    }

    /**
     * 凭据写入、标准订单与华昇回调使用同一不消费正文分支，日志不读取查询参数、请求/响应流。
     * 设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#83-安全与日志影响。
     */
    public static boolean isProtectedBodyPath(String requestUrl) {
        return isStandardOrdersPath(requestUrl)
                || ApiAccessLogPathUtils.isSupplierCredentialWritePath(requestUrl, "")
                || isHuashengCallbackPath(requestUrl);
    }

    /**
     * 安全的标准订单请求路径，避免在未知路径中泄漏可能夹带的敏感参数。
     */
    public static String safeStandardOrderUrl(String requestUrl) {
        return ApiAccessLogPathUtils.safeStandardOrderUrl(requestUrl, "");
    }

    /**
     * 安全的华昇回调请求路径，避免在未知路径中泄漏可能夹带的敏感参数。
     */
    public static String safeHuashengCallbackUrl(String requestUrl) {
        return ApiAccessLogPathUtils.safeHuashengCallbackUrl(requestUrl, "");
    }

    /**
     * 需要整键脱敏的字段名（大小写不敏感）；receiver* 前缀单独判断。
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "mobile",
            "phone",
            "telephone",
            "receivername",
            "receivermobile",
            "verifycode",
            "smscode",
            "code",
            "token",
            "accesstoken",
            "refreshtoken",
            "h5url",
            "sign",
            "secretkey",
            "secretvalue",
            "secretref",
            "apikey",
            "password"
    );

    /**
     * 需要屏蔽的请求/响应头（大小写不敏感）。
     */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            HttpHeaders.AUTHORIZATION.toLowerCase(Locale.ROOT),
            HttpHeaders.COOKIE.toLowerCase(Locale.ROOT),
            HttpHeaders.SET_COOKIE.toLowerCase(Locale.ROOT)
    );

    private AccessLogSanitizeUtils() {
    }

    /**
     * 判断是否为开放商城接口路径（禁止记录原始 body）。
     *
     * @param requestUrl 请求路径
     * @return true 表示开放接口
     */
    public static boolean isOpenMallPath(String requestUrl) {
        if (StrUtil.isBlank(requestUrl)) {
            return false;
        }
        return requestUrl.startsWith(OPEN_MALL_PATH_PREFIX)
                || "/app-api/open/mall/v1".equals(requestUrl);
    }

    /**
     * 脱敏 HTTP 头：Authorization / Cookie / Set-Cookie 替换为占位符。
     *
     * @param headers 原始头（可为 null）
     * @return 脱敏后的单值 Map；入参为 null 时返回 null
     */
    public static Map<String, String> sanitizeHeaders(MultiValueMap<String, String> headers) {
        if (headers == null) {
            return null;
        }
        MultiValueMap<String, String> sanitized = new LinkedMultiValueMap<>();
        headers.forEach((name, values) -> {
            if (isSensitiveHeader(name)) {
                sanitized.add(name, MASK);
            } else if (values != null) {
                values.forEach(value -> sanitized.add(name, value));
            }
        });
        return sanitized.toSingleValueMap();
    }

    /**
     * 对 JSON body 做递归脱敏；非 JSON body 一律省略。
     * <p>
     * 网关同样会处理 form 请求。若原样打印非 JSON 内容，手机号、验证码等字段会绕过
     * JSON 脱敏逻辑，因此选择默认省略，优先保证敏感数据不进入访问日志。
     *
     * @param body 原始 body 字符串
     * @return 脱敏后的对象或字符串，供日志打印
     */
    public static Object sanitizeJsonBody(String body) {
        if (StrUtil.isBlank(body)) {
            return body;
        }
        if (!JsonUtils.isJson(body)) {
            return BODY_OMITTED;
        }
        try {
            if (JSONUtil.isTypeJSONObject(body)) {
                JSONObject jsonObject = JSONUtil.parseObj(body);
                sanitizeJsonObject(jsonObject);
                return jsonObject;
            }
            if (JSONUtil.isTypeJSONArray(body)) {
                JSONArray jsonArray = JSONUtil.parseArray(body);
                sanitizeJsonArray(jsonArray);
                return jsonArray;
            }
        } catch (Exception ignored) {
            // 解析失败时不阻断网关日志，同时禁止回退原文
        }
        return BODY_OMITTED;
    }

    /**
     * 判断 HTTP 头是否敏感。
     * <p>
     * 除标准 Authorization/Cookie 外，兼容常见的 X-Token、X-Api-Key、X-Sign 等自定义头。
     */
    private static boolean isSensitiveHeader(String name) {
        if (StrUtil.isBlank(name)) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (SENSITIVE_HEADERS.contains(normalized)
                || normalized.contains("api-key") || normalized.contains("apikey")) {
            return true;
        }
        for (String segment : normalized.split("-")) {
            if (Set.of("authorization", "cookie", "token", "secret", "sign", "signature", "mobile").contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断字段名是否敏感（大小写不敏感；receiver 前缀匹配）。
     *
     * @param key 字段名
     * @return true 表示需要脱敏
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        // 覆盖 receiverName / receiverMobile / receiverAddress 等收货人相关字段
        return normalized.startsWith("receiver");
    }

    /**
     * 递归脱敏 JSON 对象。
     */
    private static void sanitizeJsonObject(JSONObject jsonObject) {
        if (jsonObject == null) {
            return;
        }
        // 先拷贝 key，避免遍历时修改值引发并发修改问题
        for (String key : jsonObject.keySet().toArray(new String[0])) {
            Object value = jsonObject.get(key);
            if (isSensitiveKey(key)) {
                jsonObject.set(key, MASK);
                continue;
            }
            if (value instanceof JSONObject) {
                sanitizeJsonObject((JSONObject) value);
            } else if (value instanceof JSONArray) {
                sanitizeJsonArray((JSONArray) value);
            }
        }
    }

    /**
     * 递归脱敏 JSON 数组。
     */
    private static void sanitizeJsonArray(JSONArray jsonArray) {
        if (jsonArray == null) {
            return;
        }
        for (int i = 0; i < jsonArray.size(); i++) {
            Object value = jsonArray.get(i);
            if (value instanceof JSONObject) {
                sanitizeJsonObject((JSONObject) value);
            } else if (value instanceof JSONArray) {
                sanitizeJsonArray((JSONArray) value);
            }
        }
    }

}
