package com.aifuli.framework.common.util.monitor;

import org.springframework.core.task.TaskDecorator;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Spring 执行器装饰链组合工具：保留已配置装饰器，在外层追加框架上下文，并按装饰器实例去重。
 * <p>
 * 当前 Spring 的 ThreadPoolTaskExecutor / SimpleAsyncTaskExecutor 没有公开装饰器 getter；
 * 此处集中隔离字段读取的版本兼容边界，字段缺失或类型变化必须启动失败，不能静默覆盖业务装饰器。
 * 调用者只可对这两种受支持执行器使用本工具，再通过其公开 setter 安装结果。
 * 设计见 docs/superpowers/specs/2026-10-04-logging-standardization-spec.md#24-异步多线程与后台任务jobmq的-traceid-规范。
 */
public final class TaskDecoratorUtils {

    private TaskDecoratorUtils() {
    }

    /** 读取当前装饰链并追加；链内已有同一实例时保持不变，避免 Boot 自动装饰被再次安装。 */
    public static TaskDecorator append(Object executor, TaskDecorator additional) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(additional, "additional");
        Field field = ReflectionUtils.findField(executor.getClass(), "taskDecorator", TaskDecorator.class);
        if (field == null) {
            throw new IllegalStateException("Unsupported Spring executor taskDecorator field: "
                    + executor.getClass().getName());
        }
        ReflectionUtils.makeAccessible(field);
        TaskDecorator existing = (TaskDecorator) ReflectionUtils.getField(field, executor);
        if (existing == null) {
            return additional;
        }
        List<TaskDecorator> decorators = existing instanceof DecoratorChain chain
                ? new ArrayList<>(chain.decorators) : new ArrayList<>(List.of(existing));
        if (decorators.stream().anyMatch(decorator -> decorator == additional)) {
            return existing;
        }
        decorators.add(additional);
        return new DecoratorChain(decorators);
    }

    /** 顺序装饰、逆序进入作用域；各装饰器独立负责 finally 恢复，不依赖两个 starter 初始化顺序。 */
    private static final class DecoratorChain implements TaskDecorator {
        private final List<TaskDecorator> decorators;

        private DecoratorChain(List<TaskDecorator> decorators) {
            this.decorators = List.copyOf(decorators);
        }

        @Override
        public Runnable decorate(Runnable runnable) {
            Runnable result = runnable;
            for (TaskDecorator decorator : decorators) {
                result = decorator.decorate(result);
            }
            return result;
        }
    }
}
