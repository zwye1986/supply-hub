package com.aifuli.framework.tracer.core.async;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * SLF4J MDC 异步线程上下文透传装饰器
 * <p>
 * 职责与生命周期保证：
 * 1. 任务调度前（在父线程中）：捕获父线程当前的 MDC 上下文快照；
 * 2. 任务执行时（在子线程中）：将父线程快照写入子线程 MDC，无缝继承链路 traceId；
 * 3. 任务执行完毕（在 finally 块中）：恢复执行线程原快照，原为空时清空，避免线程复用污染及同线程回退丢失调用者上下文。
 * <p>
 * 设计见 {@code docs/superpowers/specs/2026-10-04-logging-standardization-spec.md#24-异步多线程与后台任务jobmq的-traceid-规范}。
 *
 * @author 芋道源码
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return wrap(runnable);
    }

    /**
     * 便捷静态包装方法：用于 {@code CompletableFuture} 或自建线程池中的 {@link Runnable}
     *
     * @param runnable 原始任务
     * @return 携带当前父线程 MDC 的装饰任务
     */
    public static Runnable wrap(Runnable runnable) {
        if (runnable == null) {
            return null;
        }
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            // 必须在真正执行时保存原上下文，而非在提交时保存；CallerRuns/嵌套包装会复用调用线程。
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                restore(contextMap);
                runnable.run();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * 便捷静态包装方法：用于 {@link java.util.concurrent.ExecutorService#submit(Callable)} 中的 {@link Callable}
     *
     * @param callable 原始 Callable 任务
     * @param <V>      返回类型
     * @return 携带当前父线程 MDC 的装饰任务
     */
    public static <V> Callable<V> wrap(Callable<V> callable) {
        if (callable == null) {
            return null;
        }
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                restore(contextMap);
                return callable.call();
            } finally {
                restore(previous);
            }
        };
    }

    /** 安装独立快照；null/空快照必须移除本任务上下文，非空快照由 MDC 防御性复制。 */
    private static void restore(Map<String, String> contextMap) {
        if (contextMap == null || contextMap.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(contextMap);
        }
    }

}
