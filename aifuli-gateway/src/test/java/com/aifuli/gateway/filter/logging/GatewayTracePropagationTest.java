package com.aifuli.gateway.filter.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.composite.loggingevent.LoggingEventJsonProviders;
import net.logstash.logback.composite.loggingevent.LoggingEventPatternJsonProvider;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调用生产过滤器并即时编码真实日志事件，不能用 message 内嵌 traceId 代替顶层字段。
 * 设计见 docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#43-s3网关-webflux-traceid-所有权与-reactor-mdc-作用域。
 */
class GatewayTracePropagationTest {
    private static final String PROTECTED_PATH = "/admin-api/supply-chain/supplier-config/credential-items";
    private static final org.slf4j.Logger BUSINESS_LOG = LoggerFactory.getLogger(GatewayTracePropagationTest.class);
    private final AccessLogFilter accessLogFilter = new AccessLogFilter();
    private AnnotationConfigApplicationContext context;
    private JsonCapture capture;
    private Logger root;
    private Scheduler first;
    private Scheduler second;

    @BeforeEach
    void setUp() {
        MDC.clear();
        context = new AnnotationConfigApplicationContext();
        // 基线没有此包的组件：同一 fixture 在修复前后均加载实际可发现的生产 WebFilter。
        context.scan("com.aifuli.gateway.filter.tracing");
        context.refresh();
        ReflectionTestUtils.setField(accessLogFilter, "codecConfigurer", ServerCodecConfigurer.create());
        first = Schedulers.newSingle("s3-first");
        second = Schedulers.newSingle("s3-second");
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        capture = new JsonCapture();
        capture.setContext(root.getLoggerContext());
        capture.start();
        root.addAppender(capture);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(capture);
        capture.stop();
        context.close();
        first.dispose();
        second.dispose();
        MDC.clear();
    }

    @Test
    void protectedSuccessHasTopLevelTraceAcrossSchedulers() throws Exception {
        var exchange = exchange(PROTECTED_PATH, "trace-success");
        pipeline(exchange, forwarded -> Mono.defer(() -> {
                    BUSINESS_LOG.info("[S3业务][订阅] phase={}", "defer");
                    return Mono.just(1);
                }).subscribeOn(first).publishOn(second).flatMap(value -> {
                    BUSINESS_LOG.info("[S3业务][切换线程] phase={}", "publishOn");
                    return forwarded.getResponse().setComplete();
                })).block(Duration.ofSeconds(5));
        capture.awaitAccess();
        assertThat(capture.rows).hasSize(3).allSatisfy(row ->
                assertThat(row.path("traceId").asText()).isEqualTo("trace-success"));
        assertThat(accessRows()).singleElement().satisfies(row ->
                assertThat(row.path("message").asText()).contains("trace-success"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "protected,success", "protected,error", "protected,cancel",
            "ordinaryGet,success", "ordinaryGet,error", "ordinaryGet,cancel",
            "ordinaryJson,success", "ordinaryJson,error", "ordinaryJson,cancel"
    })
    void accessTerminationHasOneTopLevelTraceWithoutSecrets(String pathType, String outcome) throws Exception {
        String secret = "SYNTHETIC_S3_SECRET";
        String path = pathType.equals("protected") ? PROTECTED_PATH : "/admin-api/s3-ordinary";
        String traceId = "trace-" + pathType + "-" + outcome;
        java.util.Map<String, String> callerMdc = java.util.Map.of("traceId", "caller-before", "callerField", "keep");
        java.util.Map<String, String> firstMdc = java.util.Map.of("traceId", "first-before", "workerField", "first");
        java.util.Map<String, String> secondMdc = java.util.Map.of("traceId", "second-before", "workerField", "second");
        MDC.setContextMap(callerMdc);
        schedulerTask(first, () -> { MDC.setContextMap(firstMdc); return true; });
        schedulerTask(second, () -> { MDC.setContextMap(secondMdc); return true; });
        String url = pathType.equals("protected") ? path + "?token=" + secret : path;
        MockServerHttpRequest request = pathType.equals("ordinaryGet")
                ? MockServerHttpRequest.get(url).header("trace-id", traceId).build()
                : MockServerHttpRequest.post(url).header("trace-id", traceId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"secretValue\":\"" + secret + "\"}");
        var exchange = MockServerWebExchange.from(request);
        CountDownLatch subscribed = new CountDownLatch(1);
        Mono<Void> execution = pipeline(exchange, forwarded -> Mono.just(1).subscribeOn(first).publishOn(second)
                .flatMap(value -> {
                    if (outcome.equals("error")) {
                        return Mono.error(new IllegalStateException(secret));
                    }
                    if (outcome.equals("cancel")) {
                        return Mono.<Void>never().doOnSubscribe(ignored -> subscribed.countDown());
                    }
                    return forwarded.getResponse().setComplete();
                }));
        if (outcome.equals("cancel")) {
            reactor.core.Disposable cancellation = execution.subscribe();
            assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
            cancellation.dispose();
        } else if (outcome.equals("error")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> execution.block(Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class).hasMessage(secret);
        } else {
            execution.block(Duration.ofSeconds(5));
        }
        capture.awaitAccess();
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(callerMdc);
        assertThat(schedulerTask(first, MDC::getCopyOfContextMap)).isEqualTo(firstMdc);
        assertThat(schedulerTask(second, MDC::getCopyOfContextMap)).isEqualTo(secondMdc);
        assertThat(accessRows()).singleElement().satisfies(row -> {
            assertThat(row.path("traceId").asText()).isEqualTo(traceId);
            assertThat(row.path("message").asText()).doesNotContain(secret).contains("duration", "endTime");
        });
    }

    @Test
    void outerSpringExceptionHandlerReentersRequestTraceForStructuredLog() throws Exception {
        var exchange = exchange("/admin-api/s3-ordinary", "trace-outer-error");
        var handler = new org.springframework.web.server.handler.ExceptionHandlingWebHandler(
                ignored -> pipeline(exchange, forwarded -> Mono.delay(Duration.ofMillis(1), first)
                        .publishOn(second).then(Mono.error(new IllegalStateException("synthetic-failure")))),
                List.of(new com.aifuli.gateway.handler.GlobalExceptionHandler()));
        handler.handle(exchange).block(Duration.ofSeconds(5));
        capture.awaitAccess();
        assertThat(capture.rows).hasSize(2).allSatisfy(row ->
                assertThat(row.path("traceId").asText()).isEqualTo("trace-outer-error"));
        assertThat(capture.rows.stream().filter(row -> row.path("logger").asText()
                .equals(com.aifuli.gateway.handler.GlobalExceptionHandler.class.getName())).toList())
                .singleElement().satisfies(row -> assertThat(row.path("message").asText()).doesNotContain("trace-outer-error"));
        assertThat(exchange.getResponse().isCommitted()).isTrue();
        assertThat(exchange.getResponse().getHeaders().get("trace-id")).containsExactly("trace-outer-error");
    }

    @Test
    void concurrentRequestsKeepBusinessAndAccessJsonIsolated() throws Exception {
        int requests = 64;
        capture.expectAccesses(requests);
        reactor.core.publisher.Flux.range(0, requests).flatMap(index -> {
            String traceId = "parallel-" + index;
            var exchange = exchange(PROTECTED_PATH, traceId);
            return pipeline(exchange, forwarded -> Mono.just(index).subscribeOn(first).publishOn(second)
                    .doOnNext(value -> BUSINESS_LOG.info("[S3并发][开始] expected={}", traceId))
                    .delayElement(Duration.ofMillis(1), first).publishOn(second)
                    .doOnNext(value -> BUSINESS_LOG.info("[S3并发][结束] expected={}", traceId))
                    .then(forwarded.getResponse().setComplete()));
        }, 16).then().block(Duration.ofSeconds(10));
        capture.awaitAccess();
        assertThat(capture.rows).hasSize(requests * 3);
        java.util.Map<String, Long> groups = capture.rows.stream().collect(java.util.stream.Collectors.groupingBy(
                row -> row.path("traceId").asText(), java.util.stream.Collectors.counting()));
        assertThat(groups).hasSize(requests).allSatisfy((traceId, count) -> {
            assertThat(traceId).startsWith("parallel-");
            assertThat(count).isEqualTo(3);
        });
        capture.rows.stream().filter(row -> row.path("logger").asText().equals(GatewayTracePropagationTest.class.getName()))
                .forEach(row -> assertThat(row.path("message").asText()).endsWith("expected=" + row.path("traceId").asText()));
    }

    @Test
    void requestAndFinalResponseHeadersHaveOneOwnedValue() throws Exception {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(PROTECTED_PATH)
                .header("trace-id", "owned", "untrusted-extra"));
        pipeline(exchange, forwarded -> {
            assertThat(forwarded.getRequest().getHeaders().get("trace-id")).containsExactly("owned");
            // 模拟下游路由 addAll，必须由入口 beforeCommit 在真实提交时收敛。
            forwarded.getResponse().getHeaders().add("trace-id", "owned");
            forwarded.getResponse().getHeaders().add("trace-id", "different-downstream");
            assertThat(forwarded.getResponse().getHeaders().get("trace-id")).hasSize(3);
            return forwarded.getResponse().writeWith(Mono.just(forwarded.getResponse().bufferFactory()
                    .wrap("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }).block(Duration.ofSeconds(5));
        capture.awaitAccess();
        assertThat(exchange.getResponse().isCommitted()).isTrue();
        assertThat(exchange.getResponse().getHeaders().get("trace-id")).containsExactly("owned");
        assertThat(capture.rows).singleElement().satisfies(row -> assertThat(row.path("traceId").asText()).isEqualTo("owned"));
    }

    @Test
    void missingHeaderGeneratesOneTraceBeforeAnyDownstreamWebFilter() throws Exception {
        context.registerBean("s3Probe", WebFilter.class, () -> (exchange, chain) -> {
            BUSINESS_LOG.info("[S3入口][路由前过滤器] phase={}", "before-route");
            assertThat(MDC.get("traceId")).matches("[0-9a-f]{32}");
            assertThat(exchange.getRequest().getHeaders().get("trace-id")).containsExactly(MDC.get("traceId"));
            return chain.filter(exchange);
        });
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(PROTECTED_PATH).header("trace-id", " "));
        pipeline(exchange, forwarded -> forwarded.getResponse().setComplete()).block(Duration.ofSeconds(5));
        capture.awaitAccess();
        String traceId = exchange.getResponse().getHeaders().getFirst("trace-id");
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(exchange.getResponse().getHeaders().get("trace-id")).containsExactly(traceId);
        assertThat(capture.rows).hasSize(2).allSatisfy(row -> assertThat(row.path("traceId").asText()).isEqualTo(traceId));
    }

    @Test
    void cancellationDoesNotSubscribeToProtectedSecretBody() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean bodyRead = new java.util.concurrent.atomic.AtomicBoolean();
        var body = reactor.core.publisher.Flux.defer(() -> {
            bodyRead.set(true);
            return reactor.core.publisher.Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                    .wrap("SYNTHETIC_CANCEL_SECRET".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        });
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(PROTECTED_PATH + "?token=SYNTHETIC_CANCEL_SECRET")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("trace-id", "cancel-protected").body(body));
        CountDownLatch subscribed = new CountDownLatch(1);
        reactor.core.Disposable cancellation = pipeline(exchange, forwarded -> Mono.<Void>never()
                .doOnSubscribe(ignored -> subscribed.countDown()).subscribeOn(first)).subscribe();
        assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
        cancellation.dispose();
        capture.awaitAccess();
        assertThat(bodyRead).isFalse();
        assertThat(capture.rows).singleElement().satisfies(row -> {
            assertThat(row.path("traceId").asText()).isEqualTo("cancel-protected");
            assertThat(row.path("message").asText()).doesNotContain("SYNTHETIC_CANCEL_SECRET").contains("[omitted]");
        });
    }

    /** 直接调度探针，不通过 Reactor Hook，读取线程真正留下的 MDC。 */
    private static <T> T schedulerTask(Scheduler scheduler, java.util.concurrent.Callable<T> task) throws Exception {
        java.util.concurrent.CompletableFuture<T> result = new java.util.concurrent.CompletableFuture<>();
        scheduler.schedule(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(5, TimeUnit.SECONDS);
    }

    private MockServerWebExchange exchange(String path, String traceId) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).header("trace-id", traceId));
    }

    private Mono<Void> pipeline(MockServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        List<WebFilter> filters = new ArrayList<>(context.getBeansOfType(WebFilter.class).values());
        AnnotationAwareOrderComparator.sort(filters);
        return new FilteringWebHandler(forwarded -> accessLogFilter.filter(forwarded, chain), filters).handle(exchange);
    }

    private List<JsonNode> accessRows() {
        return capture.rows.stream().filter(row -> row.path("logger").asText().equals(AccessLogFilter.class.getName())).toList();
    }

    /** 在产生日志的线程编码，确保 MDC 取值不是收尾后的线程环境。 */
    private static final class JsonCapture extends AppenderBase<ILoggingEvent> {
        private final ObjectMapper mapper = new ObjectMapper();
        private final List<JsonNode> rows = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch accessWritten = new CountDownLatch(1);
        private final LoggingEventCompositeJsonEncoder encoder = new LoggingEventCompositeJsonEncoder();

        @Override
        public void start() {
            encoder.setContext(getContext());
            var pattern = new LoggingEventPatternJsonProvider();
            pattern.setPattern("{\"traceId\":\"%mdc{traceId}\",\"message\":\"%msg\",\"logger\":\"%logger\",\"thread\":\"%thread\"}");
            var providers = new LoggingEventJsonProviders();
            providers.addPattern(pattern);
            encoder.setProviders(providers);
            encoder.start();
            super.start();
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (!event.getLoggerName().equals(AccessLogFilter.class.getName())
                    && !event.getLoggerName().equals(com.aifuli.gateway.handler.GlobalExceptionHandler.class.getName())
                    && !event.getLoggerName().equals(GatewayTracePropagationTest.class.getName())) {
                return;
            }
            try {
                rows.add(mapper.readTree(encoder.encode(event)));
                if (event.getLoggerName().equals(AccessLogFilter.class.getName())) {
                    accessWritten.countDown();
                }
            } catch (Exception failure) {
                throw new AssertionError("真实 JSON encoder 编码失败", failure);
            }
        }

        void expectAccesses(int count) {
            accessWritten = new CountDownLatch(count);
        }

        void awaitAccess() throws InterruptedException {
            assertThat(accessWritten.await(5, TimeUnit.SECONDS)).as("真实访问日志已经收尾").isTrue();
        }

        @Override
        public void stop() {
            super.stop();
            encoder.stop();
        }
    }
}
