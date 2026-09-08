package com.aifuli.framework.jdvop.core.client;

import com.aifuli.framework.jdvop.config.JdVopProperties;
import com.aifuli.framework.jdvop.core.auth.JdVopTokenManager;
import com.aifuli.framework.jdvop.core.exception.JdVopException;
import com.aifuli.framework.jdvop.core.exception.JdVopGatewayException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基于京东 Router 协议的默认客户端。
 */
@Slf4j
public class DefaultJdVopClient implements JdVopClient {

    private static final String APPLICATION_PARAM_NAME = "360buy_param_json";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdVopProperties properties;
    private final JdVopTokenManager tokenManager;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DefaultJdVopClient(JdVopProperties properties, JdVopTokenManager tokenManager,
                              HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokenManager = tokenManager;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    @Override
    public JsonNode execute(String apiMethod, Object applicationParams) {
        return execute(properties.getServerUrl(), apiMethod, applicationParams);
    }

    @Override
    public JsonNode execute(URI serverUrl, String apiMethod, Object applicationParams) {
        if (serverUrl == null) {
            throw new IllegalArgumentException("京东 Router 地址不能为空");
        }
        if (!StringUtils.hasText(apiMethod)) {
            throw new IllegalArgumentException("京东 API 方法名不能为空");
        }
        String applicationJson = serializeApplicationParams(applicationParams);
        Map<String, String> systemParams = buildSystemParams(apiMethod);
        String signature = sign(systemParams, applicationJson);
        systemParams.put("sign", signature);

        URI requestUri = buildRequestUri(serverUrl, systemParams);
        String requestBody = APPLICATION_PARAM_NAME + "=" + encode(applicationJson);
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(properties.getReadTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 原始响应仅用于调试，避免生产环境为每次调用输出大量日志。
            log.info("京东 Router 原始响应：method={}, statusCode={}, body={}",
                    apiMethod, response.statusCode(), response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new JdVopException("京东 API 调用失败，HTTP 状态码：" + response.statusCode());
            }
            return unwrapResponse(objectMapper.readTree(response.body()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JdVopException("京东 API 请求被中断", exception);
        } catch (JdVopException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new JdVopException("京东 API 调用或响应解析失败", exception);
        }
    }

    @Override
    public <T> T execute(String apiMethod, Object applicationParams, Class<T> responseType) {
        return execute(properties.getServerUrl(), apiMethod, applicationParams, responseType);
    }

    @Override
    public <T> T execute(URI serverUrl, String apiMethod, Object applicationParams, Class<T> responseType) {
        if (responseType == null) {
            throw new IllegalArgumentException("京东 API 响应类型不能为空");
        }
        try {
            return objectMapper.treeToValue(execute(serverUrl, apiMethod, applicationParams), responseType);
        } catch (JdVopException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new JdVopException("转换京东 API 响应失败", exception);
        }
    }

    private Map<String, String> buildSystemParams(String apiMethod) {
        Map<String, String> systemParams = new TreeMap<>();
        systemParams.put("method", apiMethod);
        systemParams.put("access_token", tokenManager.getAccessToken());
        systemParams.put("app_key", properties.getAppKey());
        systemParams.put("timestamp", TIMESTAMP_FORMATTER.format(LocalDateTime.now()));
        systemParams.put("v", "2.0");
        return systemParams;
    }

    private String sign(Map<String, String> systemParams, String applicationJson) {
        Map<String, String> signParams = new TreeMap<>(systemParams);
        signParams.put(APPLICATION_PARAM_NAME, applicationJson);
        StringBuilder signSource = new StringBuilder(properties.getAppSecret());
        signParams.forEach((parameterName, parameterValue) -> {
            if (StringUtils.hasText(parameterName) && StringUtils.hasText(parameterValue)) {
                signSource.append(parameterName).append(parameterValue);
            }
        });
        signSource.append(properties.getAppSecret());
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(signSource.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (Exception exception) {
            throw new JdVopException("生成京东 API 签名失败", exception);
        }
    }

    private URI buildRequestUri(URI serverUrl, Map<String, String> systemParams) {
        StringBuilder queryBuilder = new StringBuilder();
        systemParams.forEach((parameterName, parameterValue) -> {
            if (!queryBuilder.isEmpty()) {
                queryBuilder.append('&');
            }
            queryBuilder.append(encode(parameterName)).append('=').append(encode(parameterValue));
        });
        String separator = StringUtils.hasText(serverUrl.getRawQuery()) ? "&" : "?";
        return URI.create(serverUrl + separator + queryBuilder);
    }

    private String serializeApplicationParams(Object applicationParams) {
        Object actualParams = applicationParams == null ? Collections.emptyMap() : applicationParams;
        try {
            return objectMapper.writeValueAsString(actualParams);
        } catch (Exception exception) {
            throw new JdVopException("序列化京东 API 业务参数失败", exception);
        }
    }

    private JsonNode unwrapResponse(JsonNode rootNode) {
        if (rootNode == null || !rootNode.isObject()) {
            throw new JdVopException("京东 API 返回了无效 JSON");
        }
        JsonNode errorNode = rootNode.get("error_response");
        if (errorNode != null && errorNode.isObject()) {
            throwGatewayError(errorNode);
        }

        JsonNode responseNode = unwrapMethodResponse(rootNode);
        if (isBareGatewayError(responseNode)) {
            throwGatewayError(responseNode);
        }
        return responseNode;
    }

    /**
     * 解开京东 Router 的方法响应包装层。
     *
     * <p>京东成功响应通常使用“方法名_responce”的历史拼写；部分接口可能使用
     * “方法名_response”。只识别这两类后缀，避免误拆普通业务对象。</p>
     */
    private static JsonNode unwrapMethodResponse(JsonNode rootNode) {
        Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> responseEntry = fields.next();
            String responseName = responseEntry.getKey();
            if (responseEntry.getValue().isObject()
                    && (responseName.endsWith("_responce") || responseName.endsWith("_response"))) {
                return responseEntry.getValue();
            }
        }
        return rootNode;
    }

    /**
     * 判断解包后的节点是否为京东裸网关异常。
     */
    private static boolean isBareGatewayError(JsonNode responseNode) {
        if (responseNode == null || !responseNode.isObject()) {
            return false;
        }
        String errorCode = firstTextValue(responseNode, "code", "errorCode");
        if (!StringUtils.hasText(errorCode) || isSuccessCode(errorCode)) {
            return false;
        }
        return responseNode.has("errorMessage")
                || responseNode.has("errorSolution")
                || responseNode.has("errorCode")
                || (responseNode.has("msg") && !responseNode.has("openRpcResult"));
    }

    /**
     * 将京东网关错误转换为统一异常，并保留错误码、错误消息和解决方案。
     */
    private static void throwGatewayError(JsonNode errorNode) {
        String errorCode = firstTextValue(errorNode, "code", "errorCode");
        String errorMessage = firstTextValue(
                errorNode, "zh_desc", "errorMessage", "msg", "message", "en_desc");
        String errorSolution = textValue(errorNode, "errorSolution");
        StringBuilder messageBuilder = new StringBuilder("京东 API 返回错误：")
                .append(StringUtils.hasText(errorMessage) ? errorMessage : "未知错误");
        if (StringUtils.hasText(errorSolution)) {
            messageBuilder.append("；解决方案：").append(errorSolution);
        }
        // 网关已明确拒绝签名、Token 或权限请求，不属于“可能已经受理”的未知结果。
        throw new JdVopGatewayException(errorCode, messageBuilder.toString());
    }

    /**
     * 判断京东网关返回码是否表示成功。
     */
    private static boolean isSuccessCode(String code) {
        return "0".equals(code) || "0000".equals(code);
    }

    private static String textValue(JsonNode node, String fieldName) {
        JsonNode valueNode = node.get(fieldName);
        return valueNode == null || valueNode.isNull() ? null : valueNode.asText();
    }

    private static String firstTextValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String fieldValue = textValue(node, fieldName);
            if (StringUtils.hasText(fieldValue)) {
                return fieldValue;
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

}
