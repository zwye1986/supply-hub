package com.aifuli.gateway.filter.tracing;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Map;

/**
 * Spring 管理的命名 Reactor Hook，桥接专用 Context key 到短暂 MDC 作用域。
 * 设计见 docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#43-s3网关-webflux-traceid-所有权与-reactor-mdc-作用域。
 * 信号与 request/cancel 双向包裹；订阅也包裹，避免 scheduler 上的 defer/fromCallable 漏掉 MDC。
 */
@Configuration(proxyBeanMethods = false)
public class GatewayReactorMdcConfiguration implements InitializingBean, DisposableBean {
    static final String HOOK_NAME = "aifuli.gateway.trace-mdc";
    private static int owners;
    private boolean registered;

    @Override
    public void afterPropertiesSet() {
        synchronized (GatewayReactorMdcConfiguration.class) {
            if (!registered) {
                if (owners++ == 0) {
                    Hooks.onEachOperator(HOOK_NAME, GatewayReactorMdcConfiguration::bridge);
                }
                registered = true;
            }
        }
    }

    @Override
    public void destroy() {
        synchronized (GatewayReactorMdcConfiguration.class) {
            if (registered) {
                registered = false;
                // 多 Spring Context 共用同一 Hook；只在最后一个所有者关闭时撤销，不影响其它命名 Hook。
                if (--owners == 0) {
                    Hooks.resetOnEachOperator(HOOK_NAME);
                }
            }
        }
    }

    private static Publisher<Object> bridge(Publisher<Object> source) {
        Publisher<Object> lifted = Operators.<Object, Object>lift((ignored, actual) -> new ScopedSubscriber<>(actual))
                .apply(source);
        if (lifted instanceof Mono<Object> mono) {
            return new ScopedMono(mono);
        }
        if (lifted instanceof Flux<Object> flux && !(lifted instanceof ConnectableFlux<?>)) {
            return new ScopedFlux(flux);
        }
        // Operators.lift 保留 ConnectableFlux/ParallelFlux 的类型，不将它们强转成普通 Flux。
        return lifted;
    }

    /**
     * 普通 Mono/Flux 刻意不暴露 Fuseable：避免融合 poll 绕过作用域，正确性优先于融合优化。
     * subscribe 作用域与信号作用域分别负责订阅 Supplier 和回调；二者均按完整原快照恢复。
     */
    private static final class ScopedMono extends Mono<Object> {
        private final Mono<Object> source;

        private ScopedMono(Mono<Object> source) {
            this.source = source;
        }

        @Override
        public void subscribe(CoreSubscriber<? super Object> actual) {
            inScope(actual.currentContext(), () -> source.subscribe(actual));
        }
    }

    private static final class ScopedFlux extends Flux<Object> {
        private final Flux<Object> source;

        private ScopedFlux(Flux<Object> source) {
            this.source = source;
        }

        @Override
        public void subscribe(CoreSubscriber<? super Object> actual) {
            inScope(actual.currentContext(), () -> source.subscribe(actual));
        }
    }

    /** Reactive Streams 的信号与控制路径均须恢复执行线程原 MDC，异常也不例外。 */
    private static final class ScopedSubscriber<T> implements CoreSubscriber<T>, Subscription {
        private final CoreSubscriber<? super T> actual;
        private Subscription subscription;

        private ScopedSubscriber(CoreSubscriber<? super T> actual) {
            this.actual = actual;
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription incoming) {
            if (Operators.validate(subscription, incoming)) {
                subscription = incoming;
                inScope(currentContext(), () -> actual.onSubscribe(this));
            }
        }

        @Override
        public void onNext(T value) {
            inScope(currentContext(), () -> actual.onNext(value));
        }

        @Override
        public void onError(Throwable failure) {
            inScope(currentContext(), () -> actual.onError(failure));
        }

        @Override
        public void onComplete() {
            inScope(currentContext(), actual::onComplete);
        }

        @Override
        public void request(long count) {
            inScope(currentContext(), () -> subscription.request(count));
        }

        @Override
        public void cancel() {
            inScope(currentContext(), subscription::cancel);
        }
    }

    private static void inScope(ContextView context, Runnable action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            String traceId = context.getOrDefault(TraceWebFilter.CONTEXT_KEY_TRACE_ID, null);
            if (traceId == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", traceId);
            }
            action.run();
        } finally {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }
}
