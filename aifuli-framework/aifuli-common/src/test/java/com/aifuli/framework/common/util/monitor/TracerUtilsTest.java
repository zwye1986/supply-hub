package com.aifuli.framework.common.util.monitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TracerUtils} 单元测试
 *
 * 验证规则：
 * 1. MDC 存在 traceId 时返回 MDC 中的 traceId；
 * 2. MDC 与 SkyWalking 均未激活时返回空字符串。
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#41-tracefilter-改造与-mdc-注入规范}
 */
class TracerUtilsTest {

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("当 MDC 中设置了 traceId 时，TracerUtils 应正确获取")
    void shouldReturnTraceIdFromMdc() {
        String expectedTraceId = "mdc-trace-abcdef123456";
        MDC.put(TracerUtils.MDC_KEY_TRACE_ID, expectedTraceId);

        String actualTraceId = TracerUtils.getTraceId();

        assertThat(actualTraceId).isEqualTo(expectedTraceId);
    }

    @Test
    @DisplayName("当 MDC 为空且无 Agent 时，TracerUtils 应安全返回空字符串")
    void shouldReturnEmptyWhenNoTraceIdAvailable() {
        String actualTraceId = TracerUtils.getTraceId();

        assertThat(actualTraceId).isEmpty();
    }

}
