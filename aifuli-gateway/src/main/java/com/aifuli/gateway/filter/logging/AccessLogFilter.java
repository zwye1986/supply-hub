package com.aifuli.gateway.filter.logging;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.map.MapUtil;
import com.aifuli.framework.common.util.json.JsonUtils;
import com.aifuli.gateway.util.SecurityFrameworkUtils;
import com.aifuli.gateway.util.WebFrameworkUtils;
import com.alibaba.nacos.common.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.codec.CodecConfigurer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static cn.hutool.core.date.DatePattern.NORM_DATETIME_MS_FORMATTER;

/**
 * 网关的访问日志过滤器
 *
 * 从功能上，它类似 aifuli-spring-boot-starter-web 的 ApiAccessLogFilter 过滤器
 *
 * TODO 芋艿：如果网关执行异常，不会记录访问日志，后续研究下 https://github.com/Silvmike/webflux-demo/blob/master/tests/src/test/java/ru/hardcoders/demo/webflux/web_handler/filters/logging
 *
 * @author 芋道源码
 */
@Slf4j
@Component
public class AccessLogFilter implements GlobalFilter, Ordered {

    @Resource
    private CodecConfigurer codecConfigurer;

    /**
     * 打印日志。
     * <p>
     * 脱敏仅作用于日志副本：开放接口路径不记录原始 body；
     * 所有路由屏蔽 Authorization/Cookie/Set-Cookie；其余 JSON body 递归脱敏敏感字段。
     *
     * @param gatewayLog 网关日志
     */
    private void writeAccessLog(AccessLog gatewayLog) {
        // 方式一：打印 Logger 后，通过 ELK 进行收集
        // log.info("[writeAccessLog][日志内容：{}]", JsonUtils.toJsonString(gatewayLog));

        // 方式二：调用远程服务，记录到数据库中
        // TODO 芋艿：暂未实现

        // 方式三：打印到控制台，方便排查错误
        try {
            boolean openMallPath = AccessLogSanitizeUtils.isOpenMallPath(gatewayLog.getRequestUrl());
            boolean omitBody = openMallPath || AccessLogSanitizeUtils.isProtectedBodyPath(gatewayLog.getRequestUrl());
            Map<String, Object> values = MapUtil.newHashMap(15, true); // 手工拼接，保证排序；15 保证不用扩容
            values.put("userId", gatewayLog.getUserId());
            values.put("userType", gatewayLog.getUserType());
            values.put("routeId", gatewayLog.getRoute() != null ? gatewayLog.getRoute().getId() : null);
            values.put("schema", gatewayLog.getSchema());
            values.put("requestUrl", gatewayLog.getRequestUrl());
            values.put("queryParams", omitBody ? null : (gatewayLog.getQueryParams() != null
                    ? gatewayLog.getQueryParams().toSingleValueMap() : null));
            // 开放接口与标准订单禁止记录原始请求体；其它路由对 JSON 做递归脱敏（不改动转发 body）
            values.put("requestBody", omitBody ? AccessLogSanitizeUtils.BODY_OMITTED
                    : AccessLogSanitizeUtils.sanitizeJsonBody(gatewayLog.getRequestBody()));
            // 请求头脱敏副本，避免 Authorization/Cookie 泄露
            values.put("requestHeaders", JsonUtils.toJsonString(
                    AccessLogSanitizeUtils.sanitizeHeaders(gatewayLog.getRequestHeaders())));
            values.put("userIp", gatewayLog.getUserIp());
            // 开放接口与标准订单禁止记录原始响应体；其它路由对 JSON 做递归脱敏
            values.put("responseBody", omitBody ? AccessLogSanitizeUtils.BODY_OMITTED
                    : AccessLogSanitizeUtils.sanitizeJsonBody(gatewayLog.getResponseBody()));
            values.put("responseHeaders", gatewayLog.getResponseHeaders() != null
                    ? JsonUtils.toJsonString(AccessLogSanitizeUtils.sanitizeHeaders(gatewayLog.getResponseHeaders()))
                    : null);
            values.put("httpStatus", gatewayLog.getHttpStatus());
            values.put("startTime", LocalDateTimeUtil.format(gatewayLog.getStartTime(), NORM_DATETIME_MS_FORMATTER));
            values.put("endTime", LocalDateTimeUtil.format(gatewayLog.getEndTime(), NORM_DATETIME_MS_FORMATTER));
            values.put("duration", gatewayLog.getDuration() != null ? gatewayLog.getDuration() + " ms" : null);
            log.info("[writeAccessLog][网关日志：{}]", JsonUtils.toJsonPrettyString(values));
        } catch (Exception e) {
            // 兜底处理，参见 https://gitee.com/zhijiantianya/aifuli-cloud/issues/IC9A70
            log.error("[writeAccessLog][打印网关日志时，发生异常]", e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 标准订单及凭据写入路径直接透传原流，不读取或缓存正文，不装饰响应缓冲卡密
        ServerHttpRequest request = exchange.getRequest();
        String rawPath = request.getURI().getRawPath();
        if (AccessLogSanitizeUtils.isProtectedBodyPath(rawPath)) {
            return filterProtectedBody(exchange, chain);
        }

        // 将 Request 中可以直接获取到的参数，设置到网关日志
        // TODO traceId
        AccessLog gatewayLog = new AccessLog();
        gatewayLog.setRoute(WebFrameworkUtils.getGatewayRoute(exchange));
        gatewayLog.setSchema(request.getURI().getScheme());
        gatewayLog.setRequestMethod(request.getMethod().name());
        gatewayLog.setRequestUrl(request.getURI().getRawPath());
        gatewayLog.setQueryParams(request.getQueryParams());
        gatewayLog.setRequestHeaders(request.getHeaders());
        gatewayLog.setStartTime(LocalDateTime.now());
        gatewayLog.setUserIp(WebFrameworkUtils.getClientIP(exchange));

        // 继续 filter 过滤
        MediaType mediaType = request.getHeaders().getContentType();
        if (MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType)
                || MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) { // 适合 JSON 和 Form 提交的请求
            return filterWithRequestBody(exchange, chain, gatewayLog);
        }
        return filterWithoutRequestBody(exchange, chain, gatewayLog);
    }

    /**
     * 标准订单及供应商凭据写入路径专用分支：在任何 bodyToMono/codec/buffer 操作之前直接透传原请求与响应流，
     * 仅在完成或异常时采集 method、注册操作路径、route、状态、耗时等安全元数据，
     * 保证签名正文与卡密响应不经过任何读取、重编码或缓冲。
     * 设计见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#3-gateway精确路由与不消费正文}
     * 及 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#151-凭据写入日志保护}。
     */
    private Mono<Void> filterProtectedBody(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        AccessLog gatewayLog = new AccessLog();
        gatewayLog.setRoute(WebFrameworkUtils.getGatewayRoute(exchange));
        gatewayLog.setSchema(request.getURI().getScheme());
        gatewayLog.setRequestMethod(request.getMethod().name());
        String rawPath = request.getURI().getRawPath();
        gatewayLog.setRequestUrl(AccessLogSanitizeUtils.isStandardOrdersPath(rawPath)
                ? AccessLogSanitizeUtils.safeStandardOrderUrl(rawPath) : rawPath);
        gatewayLog.setStartTime(LocalDateTime.now());
        gatewayLog.setUserIp(WebFrameworkUtils.getClientIP(exchange));
        gatewayLog.setRequestBody(AccessLogSanitizeUtils.BODY_OMITTED);
        gatewayLog.setResponseBody(AccessLogSanitizeUtils.BODY_OMITTED);

        return chain.filter(exchange)
                .doFinally(signal -> finishProtectedBodyLog(exchange, gatewayLog, signal));
    }

    /** 敏感正文透传分支的统一收尾：补齐耗时与状态后写安全访问日志，成功与异常共用同一逻辑。 */
    private void finishProtectedBodyLog(ServerWebExchange exchange, AccessLog gatewayLog, SignalType signal) {
        gatewayLog.setEndTime(LocalDateTime.now());
        gatewayLog.setDuration((int) java.time.Duration.between(gatewayLog.getStartTime(), gatewayLog.getEndTime()).toMillis());
        HttpStatus fallback = signal == SignalType.ON_ERROR ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK;
        gatewayLog.setHttpStatus(exchange.getResponse().getStatusCode() != null
                ? HttpStatus.resolve(exchange.getResponse().getStatusCode().value()) : fallback);
        writeAccessLog(gatewayLog);
    }

    private Mono<Void> filterWithoutRequestBody(ServerWebExchange exchange, GatewayFilterChain chain, AccessLog accessLog) {
        // 包装 Response，用于记录 Response Body
        ServerHttpResponseDecorator decoratedResponse = recordResponseLog(exchange, accessLog);
        return chain.filter(exchange.mutate().response(decoratedResponse).build())
                .then(Mono.fromRunnable(() -> writeAccessLog(accessLog))); // 打印日志
    }

    /**
     * 参考 {@link ModifyRequestBodyGatewayFilterFactory} 实现
     *
     * 差别主要在于使用 modifiedBody 来读取 Request Body 数据
     */
    private Mono<Void> filterWithRequestBody(ServerWebExchange exchange, GatewayFilterChain chain, AccessLog gatewayLog) {
        // 设置 Request Body 读取时，设置到网关日志（开放接口路径不缓存原始 body 到日志对象，避免敏感信息驻留）
        // 此处 codecConfigurer.getReaders() 的目的，是解决 spring.codec.max-in-memory-size 不生效
        boolean openMallPath = AccessLogSanitizeUtils.isOpenMallPath(gatewayLog.getRequestUrl());
        ServerRequest serverRequest = ServerRequest.create(exchange, codecConfigurer.getReaders());
        Mono<String> modifiedBody = serverRequest.bodyToMono(String.class).flatMap(body -> {
            // 仅非开放路径写入日志副本；转发 body 始终原样返回，不破坏网关转发
            if (!openMallPath) {
                gatewayLog.setRequestBody(body);
            }
            return Mono.just(body);
        });

        // 创建 BodyInserter 对象
        BodyInserter<Mono<String>, ReactiveHttpOutputMessage> bodyInserter = BodyInserters.fromPublisher(modifiedBody, String.class);
        // 创建 CachedBodyOutputMessage 对象
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(exchange.getRequest().getHeaders());
        // the new content type will be computed by bodyInserter
        // and then set in the request decorator
        headers.remove(HttpHeaders.CONTENT_LENGTH); // 移除
        CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, headers);
        // 通过 BodyInserter 将 Request Body 写入到 CachedBodyOutputMessage 中
        return bodyInserter.insert(outputMessage, new BodyInserterContext()).then(Mono.defer(() -> {
            // 包装 Request，用于缓存 Request Body
            ServerHttpRequest decoratedRequest = requestDecorate(exchange, headers, outputMessage);
            // 包装 Response，用于记录 Response Body
            ServerHttpResponseDecorator decoratedResponse = recordResponseLog(exchange, gatewayLog);
            // 记录普通的
            return chain.filter(exchange.mutate().request(decoratedRequest).response(decoratedResponse).build())
                    .then(Mono.fromRunnable(() -> writeAccessLog(gatewayLog))); // 打印日志

        }));
    }

    /**
     * 记录响应日志
     * 通过 DataBufferFactory 解决响应体分段传输问题。
     */
    private ServerHttpResponseDecorator recordResponseLog(ServerWebExchange exchange, AccessLog gatewayLog) {
        ServerHttpResponse response = exchange.getResponse();
        return new ServerHttpResponseDecorator(response) {

            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (body instanceof Flux) {
                    DataBufferFactory bufferFactory = response.bufferFactory();
                    // 计算执行时间
                    gatewayLog.setEndTime(LocalDateTime.now());
                    gatewayLog.setDuration((int) (LocalDateTimeUtil.between(gatewayLog.getStartTime(),
                            gatewayLog.getEndTime()).toMillis()));
                    // 设置其它字段
                    gatewayLog.setUserId(SecurityFrameworkUtils.getLoginUserId(exchange));
                    gatewayLog.setUserType(SecurityFrameworkUtils.getLoginUserType(exchange));
                    gatewayLog.setResponseHeaders(response.getHeaders());
                    gatewayLog.setHttpStatus((HttpStatus) response.getStatusCode());

                    // 获取响应类型，如果是 json 就打印（开放接口路径不把原始响应体写入日志对象）
                    boolean openMallPath = AccessLogSanitizeUtils.isOpenMallPath(gatewayLog.getRequestUrl());
                    String originalResponseContentType = exchange.getAttribute(ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR);
                    if (StringUtils.isNotBlank(originalResponseContentType)
                            && originalResponseContentType.contains("application/json")) {
                        Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                        return super.writeWith(fluxBody.buffer().map(dataBuffers -> {
                            // 读取响应内容用于转发；日志仅在非开放路径保留副本
                            byte[] content = readContent(dataBuffers);
                            if (!openMallPath) {
                                String responseResult = new String(content, StandardCharsets.UTF_8);
                                gatewayLog.setResponseBody(responseResult);
                            }

                            // 响应
                            return bufferFactory.wrap(content);
                        }));
                    }
                }
                // if body is not a flux. never got there.
                return super.writeWith(body);
            }
        };
    }

    // ========== 参考 ModifyRequestBodyGatewayFilterFactory 中的方法 ==========

    /**
     * 请求装饰器，支持重新计算 headers、body 缓存
     *
     * @param exchange 请求
     * @param headers 请求头
     * @param outputMessage body 缓存
     * @return 请求装饰器
     */
    private ServerHttpRequestDecorator requestDecorate(ServerWebExchange exchange, HttpHeaders headers, CachedBodyOutputMessage outputMessage) {
        return new ServerHttpRequestDecorator(exchange.getRequest()) {

            @Override
            public HttpHeaders getHeaders() {
                long contentLength = headers.getContentLength();
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.putAll(super.getHeaders());
                if (contentLength > 0) {
                    httpHeaders.setContentLength(contentLength);
                } else {
                    // TODO: this causes a 'HTTP/1.1 411 Length Required' // on
                    // httpbin.org
                    httpHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
                }
                return httpHeaders;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return outputMessage.getBody();
            }
        };
    }

    // ========== 参考 ModifyResponseBodyGatewayFilterFactory 中的方法 ==========

    private byte[] readContent(List<? extends DataBuffer> dataBuffers) {
        // 合并多个流集合，解决返回体分段传输
        DataBufferFactory dataBufferFactory = new DefaultDataBufferFactory();
        DataBuffer join = dataBufferFactory.join(dataBuffers);
        byte[] content = new byte[join.readableByteCount()];
        join.read(content);
        // 释放掉内存
        DataBufferUtils.release(join);
        return content;
    }

}
