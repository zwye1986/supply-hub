package com.aifuli.gateway.filter.tracing;

import cn.hutool.core.util.IdUtil;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 请求的追踪号所有者：先于路由及其它 WebFilter 确定编号，用 Context 而非入口线程 MDC 传递。
 * 设计见 docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#43-s3网关-webflux-traceid-所有权与-reactor-mdc-作用域。
 */
@Component
public class TraceWebFilter implements WebFilter, Ordered {
    public static final String HEADER_NAME_TRACE_ID = "trace-id";
    public static final String CONTEXT_KEY_TRACE_ID = "aifuli.gateway.traceId";
    private static final String EXCHANGE_TRACE_ID = TraceWebFilter.class.getName() + ".traceId";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = getOrCreateTraceId(exchange);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER_NAME_TRACE_ID, traceId)).build();
        exchange.getResponse().getHeaders().set(HEADER_NAME_TRACE_ID, traceId);
        // 下游可能 addAll 同名 Header，最终提交时由网关再次 set，禁止返回逗号拼接的多个编号。
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER_NAME_TRACE_ID, traceId);
            return Mono.empty();
        });
        ServerWebExchange traced = exchange.mutate().request(request).build();
        // 延迟构造下游链，订阅作用域覆盖过滤器同步构造时的日志及同步抛错。
        return Mono.defer(() -> chain.filter(traced))
                .contextWrite(context -> context.put(CONTEXT_KEY_TRACE_ID, traceId));
    }

    /**
     * AccessLogFilter 复用同一 exchange 编号；直接调用访问过滤器时保留原有生成能力，不代表已完成 MDC 传播。
     * 编号仅由请求 Header 或本入口生成，绝不继承复用线程上残留的 MDC。
     */
    public static String getOrCreateTraceId(ServerWebExchange exchange) {
        String existing = exchange.getAttribute(EXCHANGE_TRACE_ID);
        if (existing != null) {
            return existing;
        }
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER_NAME_TRACE_ID);
        String traceId = StringUtils.hasText(incoming) ? incoming : IdUtil.fastSimpleUUID();
        exchange.getAttributes().put(EXCHANGE_TRACE_ID, traceId);
        return traceId;
    }
}
