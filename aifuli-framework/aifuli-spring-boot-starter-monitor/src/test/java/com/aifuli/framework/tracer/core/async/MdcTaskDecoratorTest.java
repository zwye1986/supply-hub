package com.aifuli.framework.tracer.core.async;

import com.aifuli.framework.common.util.monitor.TracerUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MdcTaskDecorator} 单元测试
 *
 * 验证规则：
 * 1. 异步子线程正确继承父线程的 traceId 等 MDC 上下文；
 * 2. 无论成功或抛出异常，恢复执行线程原快照，原为空时清理；
 * 3. 父线程未设置 MDC 时，子线程安全执行且不发生空指针异常；
 * 4. 静态便捷方法 {@code wrap(Runnable)} 与 {@code wrap(Callable)} 功能完整性。
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-logging-standardization-spec.md#24-异步多线程与后台任务jobmq的-traceid-规范}。
 */
class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();
    private ExecutorService singleThreadExecutor;

    @Test
    @DisplayName("同线程执行后应恢复执行线程原有 MDC，而不是清空调用者上下文")
    void shouldRestoreExecutingThreadContext() {
        MDC.put(TracerUtils.MDC_KEY_TRACE_ID, "captured-parent");
        Runnable task = decorator.decorate(() -> {
            assertThat(MDC.get(TracerUtils.MDC_KEY_TRACE_ID)).isEqualTo("captured-parent");
            MDC.put("task-only", "discard");
        });
        MDC.put(TracerUtils.MDC_KEY_TRACE_ID, "executing-thread");
        MDC.put("executor-only", "preserve");

        task.run();

        assertThat(MDC.get(TracerUtils.MDC_KEY_TRACE_ID)).isEqualTo("executing-thread");
        assertThat(MDC.get("executor-only")).isEqualTo("preserve");
        assertThat(MDC.get("task-only")).isNull();
    }

    @Test
    void shouldRestoreContextAfterRunnableFailure() {
        MDC.put("traceId", "captured");
        Runnable task = MdcTaskDecorator.wrap((Runnable) () -> {
            assertThat(MDC.get("traceId")).isEqualTo("captured");
            MDC.put("task-only", "discard");
            throw new IllegalStateException("expected failure");
        });
        MDC.put("traceId", "previous");
        assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("traceId")).isEqualTo("previous");
        assertThat(MDC.get("task-only")).isNull();
    }

    @Test
    void shouldRestoreContextAfterCallableSuccessAndFailure() throws Exception {
        MDC.put("traceId", "captured");
        Callable<String> success = MdcTaskDecorator.wrap((Callable<String>) () -> MDC.get("traceId"));
        Callable<String> failure = MdcTaskDecorator.wrap((Callable<String>) () -> {
            throw new IllegalStateException("expected failure");
        });
        MDC.put("traceId", "previous");
        assertThat(success.call()).isEqualTo("captured");
        assertThat(MDC.get("traceId")).isEqualTo("previous");
        assertThatThrownBy(failure::call).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("traceId")).isEqualTo("previous");
    }

    @Test
    void shouldRestoreNestedScopes() {
        MDC.put("traceId", "outer-captured");
        Runnable outer = decorator.decorate(() -> {
            MDC.put("traceId", "inner-captured");
            Runnable inner = MdcTaskDecorator.wrap((Runnable) () -> {
                assertThat(MDC.get("traceId")).isEqualTo("inner-captured");
                MDC.clear();
            });
            MDC.put("traceId", "outer-running");
            inner.run();
            assertThat(MDC.get("traceId")).isEqualTo("outer-running");
        });
        MDC.put("traceId", "caller");
        outer.run();
        assertThat(MDC.get("traceId")).isEqualTo("caller");
    }

    @Test
    void shouldIsolateEmptySnapshotAndRestoreWorkerContext() throws Exception {
        Runnable emptySnapshot = decorator.decorate(() -> assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty());
        singleThreadExecutor.submit(() -> MDC.put("worker", "previous")).get(5, TimeUnit.SECONDS);
        singleThreadExecutor.submit(emptySnapshot).get(5, TimeUnit.SECONDS);
        assertThat(singleThreadExecutor.submit(() -> MDC.get("worker")).get(5, TimeUnit.SECONDS))
                .isEqualTo("previous");
    }

    @Test
    void shouldRestoreCallerContextWhenPoolIsSaturated() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(decorator);
        executor.initialize();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> busy = executor.submit(() -> {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("worker timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Thread caller = Thread.currentThread();
            MDC.put("traceId", "caller-runs");
            executor.submit(() -> {
                assertThat(Thread.currentThread()).isSameAs(caller);
                assertThat(MDC.get("traceId")).isEqualTo("caller-runs");
                MDC.clear();
            }).get(5, TimeUnit.SECONDS);
            assertThat(MDC.get("traceId")).isEqualTo("caller-runs");
            release.countDown();
            busy.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        MDC.clear();
        singleThreadExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (singleThreadExecutor != null) {
            singleThreadExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("异步 Runnable 应继承父线程 traceId 并在执行后清理子线程 MDC")
    void shouldPropagateMdcToAsyncRunnable() throws ExecutionException, InterruptedException {
        String parentTraceId = "parent-trace-888888";
        MDC.put(TracerUtils.MDC_KEY_TRACE_ID, parentTraceId);

        AtomicReference<String> childTraceId = new AtomicReference<>();
        AtomicReference<String> mdcAfterExecution = new AtomicReference<>();

        Runnable task = () -> {
            childTraceId.set(MDC.get(TracerUtils.MDC_KEY_TRACE_ID));
        };

        Runnable decorated = decorator.decorate(task);

        // 在单线程池中执行
        singleThreadExecutor.submit(decorated).get();

        // 验证子线程中读取到了父线程的 traceId
        assertThat(childTraceId.get()).isEqualTo(parentTraceId);

        // 验证执行完毕后同一个工作线程中的 MDC 已被彻底清空（防止线程复用污染）
        singleThreadExecutor.submit(() -> mdcAfterExecution.set(MDC.get(TracerUtils.MDC_KEY_TRACE_ID))).get();
        assertThat(mdcAfterExecution.get()).isNull();
    }

    @Test
    @DisplayName("静态便捷方法 wrap(Callable) 应正确透传 traceId 并清理上下文")
    void shouldPropagateMdcToCallable() throws Exception {
        String parentTraceId = "callable-trace-666666";
        MDC.put(TracerUtils.MDC_KEY_TRACE_ID, parentTraceId);

        Callable<String> callable = () -> {
            String inChild = MDC.get(TracerUtils.MDC_KEY_TRACE_ID);
            return "RESULT:" + inChild;
        };

        Future<String> future = singleThreadExecutor.submit(MdcTaskDecorator.wrap(callable));
        String result = future.get();

        assertThat(result).isEqualTo("RESULT:" + parentTraceId);

        // 再次检查子线程工作线程 MDC 已被清空
        AtomicReference<String> residual = new AtomicReference<>();
        singleThreadExecutor.submit(() -> residual.set(MDC.get(TracerUtils.MDC_KEY_TRACE_ID))).get();
        assertThat(residual.get()).isNull();
    }

    @Test
    @DisplayName("子任务抛出异常时，finally 块仍须确保子线程 MDC 被彻底清理")
    void shouldCleanUpMdcEvenWhenTaskThrowsException() throws Exception {
        String parentTraceId = "error-trace-999999";
        MDC.put(TracerUtils.MDC_KEY_TRACE_ID, parentTraceId);

        Runnable failingTask = () -> {
            assertThat(MDC.get(TracerUtils.MDC_KEY_TRACE_ID)).isEqualTo(parentTraceId);
            throw new RuntimeException("Simulated async failure");
        };

        Runnable decorated = decorator.decorate(failingTask);

        assertThatThrownBy(() -> singleThreadExecutor.submit(decorated).get())
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated async failure");

        // 检查异常退出后工作线程 MDC 依然被安全清除
        AtomicReference<String> residual = new AtomicReference<>();
        singleThreadExecutor.submit(() -> residual.set(MDC.get(TracerUtils.MDC_KEY_TRACE_ID))).get(5, TimeUnit.SECONDS);
        assertThat(residual.get()).isNull();
    }

    @Test
    @DisplayName("父线程无 MDC 上下文时，子任务应安全执行且不发生空指针")
    void shouldHandleEmptyParentMdcSafely() throws ExecutionException, InterruptedException {
        MDC.clear();

        AtomicBoolean executed = new AtomicBoolean(false);
        Runnable task = () -> executed.set(true);

        Runnable decorated = decorator.decorate(task);
        singleThreadExecutor.submit(decorated).get();

        assertThat(executed.get()).isTrue();
    }

}
