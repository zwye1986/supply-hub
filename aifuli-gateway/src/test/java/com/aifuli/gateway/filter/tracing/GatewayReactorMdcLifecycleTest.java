package com.aifuli.gateway.filter.tracing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证生产 Hook 的 Reactive Streams 控制路径、嵌套作用域、融合边界及 Spring 生命周期。
 * 设计见 docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#43-s3网关-webflux-traceid-所有权与-reactor-mdc-作用域。
 */
class GatewayReactorMdcLifecycleTest {
    private static final String KEY = TraceWebFilter.CONTEXT_KEY_TRACE_ID;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        MDC.clear();
        context = new AnnotationConfigApplicationContext(GatewayReactorMdcConfiguration.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
        MDC.clear();
    }

    @Test
    void onSubscribeRequestOnNextAndCancelHaveTraceAndRestoreCaller() {
        Map<String, String> caller = Map.of("traceId", "caller", "tenant", "keep");
        MDC.setContextMap(caller);
        List<String> seen = new ArrayList<>();
        Publisher<Integer> source = subscriber -> subscriber.onSubscribe(new Subscription() {
            @Override
            public void request(long count) {
                seen.add("request:" + MDC.get("traceId"));
                assertThat(count).isEqualTo(1);
                subscriber.onNext(1);
            }

            @Override
            public void cancel() {
                seen.add("cancel:" + MDC.get("traceId"));
                MDC.put("temporary", "must-restore");
            }
        });
        Mono.fromDirect(source).subscribe(new BaseSubscriber<>() {
            @Override
            public Context currentContext() {
                return Context.of(KEY, "control");
            }

            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                seen.add("onSubscribe:" + MDC.get("traceId"));
                request(1);
            }

            @Override
            protected void hookOnNext(Integer value) {
                seen.add("onNext:" + MDC.get("traceId"));
                cancel();
            }
        });
        assertThat(seen).containsExactly("onSubscribe:control", "request:control", "onNext:control", "cancel:control");
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(caller);
    }

    @Test
    void completionAndErrorCallbacksRestoreFullCallerContext() {
        Map<String, String> caller = Map.of("traceId", "caller", "tenant", "keep");
        MDC.setContextMap(caller);
        List<String> seen = new ArrayList<>();
        Mono.just(1).doOnSuccess(value -> {
            seen.add("complete:" + MDC.get("traceId"));
            MDC.put("temporary", "must-restore");
        }).contextWrite(Context.of(KEY, "complete")).block(Duration.ofSeconds(5));
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(caller);
        assertThatThrownBy(() -> Mono.fromCallable(() -> {
                    assertThat(MDC.get("traceId")).isEqualTo("failure");
                    MDC.clear();
                    throw new IllegalStateException("expected");
                }).doOnError(failure -> seen.add("error:" + MDC.get("traceId")))
                .contextWrite(Context.of(KEY, "failure")).block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessage("expected");
        assertThat(seen).containsExactly("complete:complete", "error:failure");
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(caller);
    }

    @Test
    void absentContextRemovesOnlyStaleTraceInsideCallback() {
        Map<String, String> caller = Map.of("traceId", "stale", "tenant", "keep");
        MDC.setContextMap(caller);
        Mono.fromRunnable(() -> {
            assertThat(MDC.get("traceId")).isNull();
            assertThat(MDC.get("tenant")).isEqualTo("keep");
            MDC.put("temporary", "must-restore");
        }).block(Duration.ofSeconds(5));
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(caller);
    }

    @Test
    void nestedTraceRestoresOuterThenEmptyCaller() {
        Mono.fromRunnable(() -> {
            assertThat(MDC.get("traceId")).isEqualTo("outer");
            Mono.fromRunnable(() -> assertThat(MDC.get("traceId")).isEqualTo("inner"))
                    .contextWrite(Context.of(KEY, "inner")).block(Duration.ofSeconds(5));
            assertThat(MDC.get("traceId")).isEqualTo("outer");
        }).contextWrite(Context.of(KEY, "outer")).block(Duration.ofSeconds(5));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void scalarRangeFilterParallelAndConnectableKeepSignalSemantics() {
        var result = Flux.range(1, 10).map(value -> {
                    assertThat(MDC.get("traceId")).isEqualTo("range");
                    return value * 2;
                }).filter(value -> value % 4 == 0).collectList().contextWrite(Context.of(KEY, "range"))
                .block(Duration.ofSeconds(5));
        assertThat(result).containsExactly(4, 8, 12, 16, 20);
        assertThat(Flux.range(1, 6).parallel(2).runOn(reactor.core.scheduler.Schedulers.parallel())
                .map(value -> {
                    assertThat(MDC.get("traceId")).isEqualTo("parallel");
                    return value;
                }).sequential().collectList().contextWrite(Context.of(KEY, "parallel"))
                .block(Duration.ofSeconds(5))).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
        ConnectableFlux<Integer> connectable = Flux.range(1, 3).publish();
        assertThat(connectable.autoConnect(1).collectList().contextWrite(Context.of(KEY, "connectable"))
                .block(Duration.ofSeconds(5))).containsExactly(1, 2, 3);
    }

    @Test
    void lastSpringOwnerRemovesOnlyItsNamedHook() {
        AtomicInteger otherHookCalls = new AtomicInteger();
        Hooks.onEachOperator("s3-unrelated-hook", publisher -> {
            otherHookCalls.incrementAndGet();
            return publisher;
        });
        try (var secondOwner = new AnnotationConfigApplicationContext(GatewayReactorMdcConfiguration.class)) {
            context.close();
            assertThat(Mono.fromCallable(() -> MDC.get("traceId")).contextWrite(Context.of(KEY, "still-active"))
                    .block(Duration.ofSeconds(5))).isEqualTo("still-active");
        } finally {
            // 新装配 Publisher 应不再应用网关桥接；其它命名 Hook 仍然有效。
            MDC.put("traceId", "caller");
            int before = otherHookCalls.get();
            try {
                assertThat(Mono.fromCallable(() -> MDC.get("traceId")).contextWrite(Context.of(KEY, "removed"))
                        .block(Duration.ofSeconds(5))).isEqualTo("caller");
                assertThat(otherHookCalls.get()).isGreaterThan(before);
            } finally {
                Hooks.resetOnEachOperator("s3-unrelated-hook");
            }
        }
    }
}
