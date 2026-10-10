package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 扫描生命周期：关闭不启动线程、非正配置启动拒绝、每轮新编号、MDC完整恢复、异常轮不伪造成功。
 * 设计 §6.1～§6.3、§9。
 */
class BuyerSelectionCatalogSweepLifecycleTest {

    private final BuyerSelectionCatalogSweepService sweep = mock(BuyerSelectionCatalogSweepService.class);
    private final BuyerSelectionCatalogProperties properties = new BuyerSelectionCatalogProperties();
    private final BuyerSelectionCatalogSweepLifecycle lifecycle =
            new BuyerSelectionCatalogSweepLifecycle(sweep, properties);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void disabledSweepStartsNoThreadAndMakesNoAttempt() {
        properties.setSweepEnabled(false);
        lifecycle.start();
        verify(sweep, never()).sweepOneBatch();
        lifecycle.stop();
    }

    @Test
    void nonPositiveExplicitConfigurationIsRejectedAtStartup() {
        properties.setSweepBatchSize(0);
        assertThatThrownBy(lifecycle::start).isInstanceOf(IllegalStateException.class);
        properties.setSweepBatchSize(null);
        properties.setSweepDelaySeconds(-1);
        assertThatThrownBy(lifecycle::start).isInstanceOf(IllegalStateException.class);
        properties.setSweepDelaySeconds(null);
        properties.setMaxEvaluationAgeSeconds(0);
        assertThatThrownBy(lifecycle::start).isInstanceOf(IllegalStateException.class);
        verify(sweep, never()).sweepOneBatch();
    }

    @Test
    void eachRoundInstallsFreshTraceAndExecutionId() {
        AtomicReference<String> firstTrace = new AtomicReference<>();
        AtomicReference<String> firstExecution = new AtomicReference<>();
        AtomicReference<String> secondTrace = new AtomicReference<>();
        AtomicReference<String> secondExecution = new AtomicReference<>();
        when(sweep.sweepOneBatch()).thenAnswer(invocation -> {
            if (firstTrace.get() == null) {
                firstTrace.set(MDC.get(TracerUtils.MDC_KEY_TRACE_ID));
                firstExecution.set(MDC.get("executionId"));
            } else {
                secondTrace.set(MDC.get(TracerUtils.MDC_KEY_TRACE_ID));
                secondExecution.set(MDC.get("executionId"));
            }
            return new BuyerSelectionCatalogSweepService.SweepBatch(0, 0, 0, 0);
        });

        lifecycle.runOneRound();
        lifecycle.runOneRound();

        assertThat(firstTrace.get()).isNotBlank().doesNotContain("-");
        assertThat(firstExecution.get()).isNotBlank().doesNotContain("-");
        assertThat(secondTrace.get()).isNotBlank().isNotEqualTo(firstTrace.get());
        assertThat(secondExecution.get()).isNotBlank().isNotEqualTo(firstExecution.get());
    }

    @Test
    void repeatedBatchQueryFailuresAreRateLimitedWithFirstCause() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BuyerSelectionCatalogSweepLifecycle.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(sweep.sweepOneBatch()).thenThrow(new IllegalStateException("db down"));
            lifecycle.runOneRound();
            lifecycle.runOneRound();
            var warnings = appender.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN).toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.getFirst().getThrowableProxy()).isNotNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void independentRoundDoesNotInheritOldOriginOrBusinessContext() {
        Map<String, String> previous = Map.of("traceId", "old", "originTraceId", "stale-origin", "orderNo", "old-order");
        MDC.setContextMap(previous);
        when(sweep.sweepOneBatch()).thenAnswer(invocation -> {
            assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("traceId", "executionId");
            return new BuyerSelectionCatalogSweepService.SweepBatch(0, 0, 0, 0);
        });
        lifecycle.runOneRound();
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
    }

    @Test
    void roundRestoresOriginalThreadContextWhenEmpty() {
        when(sweep.sweepOneBatch()).thenReturn(new BuyerSelectionCatalogSweepService.SweepBatch(0, 0, 0, 0));
        MDC.put("traceId", "caller-trace");
        lifecycle.runOneRound();
        // 调用者上下文完整恢复，不被本轮编号污染。
        assertThat(MDC.get(TracerUtils.MDC_KEY_TRACE_ID)).isEqualTo("caller-trace");
        assertThat(MDC.get("executionId")).isNull();
    }

    @Test
    void roundClearsContextWhenOriginalWasEmpty() {
        when(sweep.sweepOneBatch()).thenReturn(new BuyerSelectionCatalogSweepService.SweepBatch(0, 0, 0, 0));
        lifecycle.runOneRound();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void failingRoundIsContainedAndDoesNotFakeSuccess() {
        when(sweep.sweepOneBatch()).thenThrow(new IllegalStateException("db down"));
        MDC.put("traceId", "caller-trace");
        assertThatCode(lifecycle::runOneRound).doesNotThrowAnyException();
        assertThat(MDC.get(TracerUtils.MDC_KEY_TRACE_ID)).isEqualTo("caller-trace");
    }
}
