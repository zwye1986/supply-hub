package com.aifuli.framework.rpc.core.interceptor;

import com.aifuli.framework.common.util.monitor.TracerUtils;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TraceRequestInterceptor} 单元测试
 *
 * 验证规则：
 * 1. 当 MDC 中存在 traceId 时，向 Feign RequestTemplate 添加 trace-id 请求头；
 * 2. 当 MDC 中无 traceId 时，不添加空的 trace-id 请求头。
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#42-feign-跨微服务链路透传}
 */
class TraceRequestInterceptorTest {

    private TraceRequestInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TraceRequestInterceptor();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("当 MDC 中存在 traceId 时，Feign 拦截器应向下游请求头注入 trace-id")
    void shouldPropagateTraceIdFromMdc() {
        String expectedTraceId = "feign-trace-888888";
        MDC.put(TracerUtils.MDC_KEY_TRACE_ID, expectedTraceId);

        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> traceHeaders = template.headers().get(TraceRequestInterceptor.HEADER_NAME_TRACE_ID);
        assertThat(traceHeaders).isNotNull().containsExactly(expectedTraceId);
    }

    @Test
    @DisplayName("当 MDC 中无 traceId 时，Feign 拦截器不应注入 trace-id 请求头")
    void shouldNotAddHeaderWhenTraceIdAbsent() {
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        Collection<String> traceHeaders = template.headers().get(TraceRequestInterceptor.HEADER_NAME_TRACE_ID);
        assertThat(traceHeaders).isNull();
    }

}
