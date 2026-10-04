package com.aifuli.framework.tracer.core.async;

import com.aifuli.framework.common.util.monitor.TracerUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MdcTaskDecorator} 单元测试
 *
 * 验证规则：
 * 1. 异步子线程正确继承父线程的 traceId 等 MDC 上下文；
 * 2. 子线程任务执行完毕后，无论成功或抛出异常，MDC 必须严格执行清理；
 * 3. 父线程未设置 MDC 时，子线程安全执行且不发生空指针异常；
 * 4. 静态便捷方法 {@code wrap(Runnable)} 与 {@code wrap(Callable)} 功能完整性。
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-logging-standardization-spec.md#24-异步多线程与后台任务jobmq的-traceid-规范}。
 */
class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();
    private ExecutorService singleThreadExecutor;

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
    void shouldCleanUpMdcEvenWhenTaskThrowsException() {
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
        singleThreadExecutor.submit(() -> residual.set(MDC.get(TracerUtils.MDC_KEY_TRACE_ID)));
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
