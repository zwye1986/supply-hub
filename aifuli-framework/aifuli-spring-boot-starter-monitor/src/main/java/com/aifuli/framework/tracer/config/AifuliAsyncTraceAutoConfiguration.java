package com.aifuli.framework.tracer.config;

import com.aifuli.framework.tracer.core.async.MdcTaskDecorator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;

/**
 * 异步链路追踪自动配置类：注册 MdcTaskDecorator 实现线程池上下文自动透传
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
    public TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

}
