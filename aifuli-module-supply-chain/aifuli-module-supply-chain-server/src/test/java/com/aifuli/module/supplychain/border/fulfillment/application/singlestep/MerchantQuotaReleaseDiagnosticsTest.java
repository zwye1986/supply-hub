package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.composite.loggingevent.LoggingEventJsonProviders;
import net.logstash.logback.composite.loggingevent.LoggingEventPatternJsonProvider;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/** 单调时间、真实Logback事件与兼容encoder验证；不把message计数假称ES顶层结构化字段。设计§6.4。 */
class MerchantQuotaReleaseDiagnosticsTest {
    private final AtomicLong nanos = new AtomicLong();
    private final MerchantQuotaReleaseDiagnostics diagnostics = new MerchantQuotaReleaseDiagnostics(nanos::get);
    private final Logger logger = (Logger) LoggerFactory.getLogger(MerchantQuotaReleaseDiagnostics.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Level previous;
    @BeforeEach void start() {
        previous = logger.getLevel(); logger.setLevel(Level.INFO);
        captured.start(); logger.addAppender(captured);
    }
    @AfterEach void stop() { logger.detachAppender(captured); captured.stop(); logger.setLevel(previous); }
    private void fail(String operation) {
        diagnostics.failed(operation, 1, new IllegalStateException("SECRET-key", new java.sql.SQLException("SECRET-sql")));
    }

    @Test void firstFailureKeepsSafeCauseButThousandsOfOrdersShareOneBucket() {
        for (int i = 0; i < 20000; i++) fail("OP-" + i);
        assertThat(captured.list).hasSize(1);
        var event = captured.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("[merchantQuotaRelease]", "operationNo=OP-0", "totalCount=1", "suppressedCount=0").doesNotContain("SECRET");
        assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).contains("java.sql.SQLException").doesNotContain("SECRET");
    }

    @Test void continuedFailuresSummarizeAtWindowBoundaryWithoutRepeatingStack() {
        fail("first");
        for (int i = 0; i < 99; i++) fail("repeat-" + i);
        nanos.set(Duration.ofSeconds(60).toNanos() - 1); fail("before-boundary");
        assertThat(captured.list).hasSize(1);
        nanos.incrementAndGet(); fail("boundary");
        assertThat(captured.list).hasSize(2);
        var summary = captured.list.getLast();
        assertThat(summary.getLevel()).isEqualTo(Level.WARN);
        assertThat(summary.getThrowableProxy()).isNull();
        assertThat(summary.getFormattedMessage()).contains("windowMs=60000", "totalCount=101", "suppressedCount=100");
        fail("suppressed-again");
        assertThat(captured.list).hasSize(2);
        nanos.addAndGet(Duration.ofSeconds(60).toNanos()); fail("next-window");
        assertThat(captured.list.getLast().getFormattedMessage()).contains("totalCount=2", "suppressedCount=1");
        diagnostics.recovered("after-two-windows", 1);
        assertThat(captured.list.getLast().getFormattedMessage()).contains("totalCount=104", "suppressedCount=101");
    }

    @Test void recoveryReportsIncidentTotalsOnlyOnceAndNewFailureStartsFresh() {
        diagnostics.recovered("empty", 1); assertThat(captured.list).isEmpty();
        fail("A"); fail("B");
        nanos.set(Duration.ofSeconds(5).toNanos()); diagnostics.recovered("C", 2);
        assertThat(captured.list).hasSize(2);
        var recovered = captured.list.getLast();
        assertThat(recovered.getLevel()).isEqualTo(Level.INFO);
        assertThat(recovered.getFormattedMessage()).contains("[merchantQuotaReleaseRecovered]", "windowMs=5000", "totalCount=2", "suppressedCount=1");
        assertThat(recovered.getThrowableProxy()).isNull();
        diagnostics.recovered("again", 3); assertThat(captured.list).hasSize(2);
        fail("new-incident");
        assertThat(captured.list.getLast().getLevel()).isEqualTo(Level.ERROR);
        assertThat(captured.list.getLast().getFormattedMessage()).contains("totalCount=1", "suppressedCount=0");
    }

    @Test void concurrentFailuresHaveOneFirstCauseAndExactRecoveryCount() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 1000; i++) { int id = i; futures.add(pool.submit(() -> fail("OP-" + id))); }
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        diagnostics.recovered("released", 1);
        assertThat(captured.list).hasSize(2);
        assertThat(captured.list.getLast().getFormattedMessage()).contains("totalCount=1000", "suppressedCount=999");
    }

    @Test void compatibilityEncoderKeepsSingleLineMessageTraceAndSafeCauseWithoutPretendingTopLevelCounters() throws Exception {
        String oldTrace = MDC.get("traceId");
        try {
            MDC.put("traceId", "quota-release-trace"); fail("OP-json");
            assertThat(captured.list).hasSize(1);
            // 从共享实际片段读取兼容pattern；不手写另一份字段合同，也不修改生产encoder。
            var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                    getClass().getResourceAsStream("/logback-elk-appender.xml"));
            String pattern = document.getElementsByTagName("pattern").item(1).getTextContent()
                    .replace("${APP_NAME}", "supply-chain-server").replace("${ACTIVE_PROFILE}", "test");
            var context = new LoggerContext();
            try {
                var provider = new LoggingEventPatternJsonProvider(); provider.setPattern(pattern);
                var providers = new LoggingEventJsonProviders(); providers.addPattern(provider);
                var encoder = new LoggingEventCompositeJsonEncoder(); encoder.setContext(context); encoder.setProviders(providers); encoder.start();
                try {
                    String json = new String(encoder.encode(captured.list.getFirst()), java.nio.charset.StandardCharsets.UTF_8);
                    var parsed = new ObjectMapper().readTree(json);
                    assertThat(parsed.path("traceId").asText()).isEqualTo("quota-release-trace");
                    assertThat(parsed.path("app_name").asText()).isEqualTo("supply-chain-server");
                    assertThat(parsed.path("message").asText()).contains("totalCount=1").doesNotContain("\n", "\r", "SECRET");
                    assertThat(parsed.path("stack_trace").asText()).contains("java.sql.SQLException").doesNotContain("SECRET");
                    assertThat(parsed.has("totalCount")).isFalse(); // 兼容边界，未新增顶层契约。
                } finally { encoder.stop(); }
            } finally { context.stop(); }
        } finally { if (oldTrace == null) MDC.remove("traceId"); else MDC.put("traceId", oldTrace); }
    }
}
