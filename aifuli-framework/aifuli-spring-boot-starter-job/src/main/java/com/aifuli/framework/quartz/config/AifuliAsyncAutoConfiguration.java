package com.aifuli.framework.quartz.config;

import com.alibaba.ttl.TtlRunnable;
import com.aifuli.framework.common.util.monitor.TaskDecoratorUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

/**
 * 异步任务 Configuration：保留原 TTL 透传，并与 MDC/业务装饰器组合而非互相覆盖。
 * 设计见 docs/superpowers/specs/2026-10-04-logging-standardization-spec.md#24-异步多线程与后台任务jobmq的-traceid-规范。
 */
@AutoConfiguration
@EnableAsync
public class AifuliAsyncAutoConfiguration {

    @Bean
    public static BeanPostProcessor threadPoolTaskExecutorBeanPostProcessor() {
        return new BeanPostProcessor() {

            private final TaskDecorator ttlDecorator = TtlRunnable::get;

            @Override
            @SuppressWarnings("PatternVariableCanBeUsed")
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                // 处理 ThreadPoolTaskExecutor
                if (bean instanceof ThreadPoolTaskExecutor) {
                    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) bean;
                    executor.setTaskDecorator(TaskDecoratorUtils.append(executor, ttlDecorator));
                    return executor;
                }
                // 处理 SimpleAsyncTaskExecutor
                // 参考 https://t.zsxq.com/CBoks 增加
                if (bean instanceof SimpleAsyncTaskExecutor) {
                    SimpleAsyncTaskExecutor executor = (SimpleAsyncTaskExecutor) bean;
                    executor.setTaskDecorator(TaskDecoratorUtils.append(executor, ttlDecorator));
                    return executor;
                }
                return bean;
            }

        };
    }

}
