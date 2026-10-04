package com.aifuli.framework.rpc.config;

import com.aifuli.framework.rpc.core.interceptor.TraceRequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * RPC 自动配置类：注册 TraceRequestInterceptor 实现 Feign 链路透传
 *
 * 设计见 {@code docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md#42-feign-跨微服务链路透传}
 *
 * @author 芋道源码
 */
@AutoConfiguration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class AifuliRpcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceRequestInterceptor traceRequestInterceptor() {
        return new TraceRequestInterceptor();
    }

}
