package com.aifuli.framework.tracer.core.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TraceFilter} 单元测试
 *
 * 验证规则：
 * 1. 继承上游请求头中的 trace-id 并设置到 MDC 和响应头；
 * 2. 缺失请求头且无 Agent 时，自动生成唯一 traceId 并设置到 MDC 和响应头；
 * 3. 过滤器链执行完毕后，MDC 中的 traceId 严格清理，防止线程复用污染。
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#41-tracefilter-改造与-mdc-注入规范}
 */
class TraceFilterTest {

    private TraceFilter traceFilter;

    @BeforeEach
    void setUp() {
        traceFilter = new TraceFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("当请求头包含 trace-id 时，应继承该 trace-id 并写入 MDC 和响应头，结束后清理 MDC")
    void shouldInheritTraceIdFromRequestHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String expectedTraceId = "trace-inherited-123456";
        request.addHeader(TraceFilter.HEADER_NAME_TRACE_ID, expectedTraceId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcInChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                mdcInChain.set(MDC.get(TraceFilter.MDC_KEY_TRACE_ID));
            }
        };

        traceFilter.doFilter(request, response, chain);

        // 验证链中 MDC 取得上游 traceId
        assertThat(mdcInChain.get()).isEqualTo(expectedTraceId);
        // 验证响应头回填上游 traceId
        assertThat(response.getHeader(TraceFilter.HEADER_NAME_TRACE_ID)).isEqualTo(expectedTraceId);
        // 验证过滤结束后 MDC 严格清理
        assertThat(MDC.get(TraceFilter.MDC_KEY_TRACE_ID)).isNull();
    }

    @Test
    @DisplayName("当请求头无 trace-id 且无 Agent 时，应自动生成 UUID 并写入 MDC 和响应头，结束后清理 MDC")
    void shouldGenerateTraceIdWhenHeaderAbsent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcInChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                mdcInChain.set(MDC.get(TraceFilter.MDC_KEY_TRACE_ID));
            }
        };

        traceFilter.doFilter(request, response, chain);

        // 验证链中 MDC 自动生成且不为空
        assertThat(mdcInChain.get()).isNotBlank();
        // 验证响应头具有相同的 traceId
        assertThat(response.getHeader(TraceFilter.HEADER_NAME_TRACE_ID)).isEqualTo(mdcInChain.get());
        // 验证过滤结束后 MDC 严格清理
        assertThat(MDC.get(TraceFilter.MDC_KEY_TRACE_ID)).isNull();
    }

}
