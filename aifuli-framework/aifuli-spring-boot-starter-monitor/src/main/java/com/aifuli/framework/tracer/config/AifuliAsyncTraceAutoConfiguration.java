package com.aifuli.framework.tracer.config;

import com.aifuli.framework.tracer.core.async.MdcTaskDecorator;
import com.aifuli.framework.common.util.monitor.TaskDecoratorUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步链路追踪自动配置类：注册 MDC 装饰器，并实际组合到 Spring 管理的执行器。
 * 原有 TTL 与业务装饰器均保留；不处理原生 ExecutorService，也不改变执行器容量或拒绝策略。
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-logging-standardization-spec.md#24-异步多线程与后台任务jobmq的-traceid-规范}。
 *
 * @author 芋道源码
 */
@AutoConfiguration
@ConditionalOnClass(TaskDecorator.class)
@ConditionalOnProperty(prefix = "aifuli.tracer", value = "enable", matchIfMissing = true)
public class AifuliAsyncTraceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MdcTaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    /**
     * 懒读取装饰器 Bean，避免创建 Bean 后处理器时提前实例化业务依赖。
     * 所有装饰器按 Spring 顺序加入已有链，Boot 已安装的同一实例不会重复执行。
     */
    @Bean
    public static BeanPostProcessor mdcTaskExecutorBeanPostProcessor(ObjectProvider<TaskDecorator> decorators) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof ThreadPoolTaskExecutor executor) {
                    decorators.orderedStream().forEach(decorator ->
                            executor.setTaskDecorator(TaskDecoratorUtils.append(executor, decorator)));
                } else if (bean instanceof SimpleAsyncTaskExecutor executor) {
                    decorators.orderedStream().forEach(decorator ->
                            executor.setTaskDecorator(TaskDecoratorUtils.append(executor, decorator)));
                }
                return bean;
            }
        };
    }

}
