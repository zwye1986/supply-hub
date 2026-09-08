package com.aifuli.framework.jdvop.core.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;

/**
 * 京东开放平台通用客户端。
 */
public interface JdVopClient {

    /**
     * 调用京东开放平台接口并返回已经解包的 JSON。
     *
     * @param apiMethod 京东 API 方法名
     * @param applicationParams 业务参数，对应 {@code 360buy_param_json}
     * @return 已去除最外层方法响应节点的 JSON
     */
    JsonNode execute(String apiMethod, Object applicationParams);

    /**
     * 使用指定 Router 地址调用京东开放平台接口。
     *
     * @param serverUrl 京东 Router 地址
     * @param apiMethod 京东 API 方法名
     * @param applicationParams 业务参数，对应 {@code 360buy_param_json}
     * @return 已去除最外层方法响应节点的 JSON
     */
    JsonNode execute(URI serverUrl, String apiMethod, Object applicationParams);

    /**
     * 调用京东开放平台接口并转换为指定类型。
     *
     * @param apiMethod 京东 API 方法名
     * @param applicationParams 业务参数
     * @param responseType 响应类型
     * @return 类型化响应
     * @param <T> 响应类型
     */
    <T> T execute(String apiMethod, Object applicationParams, Class<T> responseType);

    /**
     * 使用指定 Router 地址调用京东开放平台接口并转换为指定类型。
     *
     * @param serverUrl 京东 Router 地址
     * @param apiMethod 京东 API 方法名
     * @param applicationParams 业务参数
     * @param responseType 响应类型
     * @return 类型化响应
     * @param <T> 响应类型
     */
    <T> T execute(URI serverUrl, String apiMethod, Object applicationParams, Class<T> responseType);

}
