package com.aifuli.framework.quartz.config;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.aifuli.framework.tracer.config.AifuliAsyncTraceAutoConfiguration;
import com.aifuli.framework.tracer.core.async.MdcTaskDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.Async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通过真实 Spring Bean 生命周期验证 TTL、MDC 与业务装饰器共同生效，不用反射断言代替任务执行。
 * 设计见 docs/superpowers/specs/2026-10-04-logging-standardization-spec.md#24-异步多线程与后台任务jobmq的-traceid-规范。
 */
class AsyncContextCompositionTest {

    private final TransmittableThreadLocal<String> tenant = new TransmittableThreadLocal<>();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AifuliAsyncAutoConfiguration.class,
                    AifuliAsyncTraceAutoConfiguration.class, TaskExecutionAutoConfiguration.class));

    @AfterEach
    void cleanUp() {
        MDC.clear();
        tenant.remove();
    }

    @Test
    void shouldPreserveMdcTtlAndExplicitDecorator() {
        runner.withUserConfiguration(ExplicitExecutorConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);
            // 先启动工作线程，排除 InheritableThreadLocal 首次建线程造成的假透传。
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            AtomicInteger calls = context.getBean(AtomicInteger.class);
            int before = calls.get();
            MDC.put("traceId", "http-trace");
            tenant.set("tenant-request");

            Snapshot captured = executor.submit(() -> new Snapshot(MDC.get("traceId"), tenant.get()))
                    .get(5, TimeUnit.SECONDS);

            assertThat(captured.traceId()).isEqualTo("http-trace");
            assertThat(captured.tenant()).isEqualTo("tenant-request");
            assertThat(calls.get()).isEqualTo(before + 1);
            assertThat(MDC.get("traceId")).isEqualTo("http-trace");
        });
    }

    @Test
    void shouldPropagateWithBootCreatedExecutor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            MDC.put("traceId", "boot-trace");
            tenant.set("boot-tenant");
            Snapshot result = executor.submit(() -> new Snapshot(MDC.get("traceId"), tenant.get()))
                    .get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(new Snapshot("boot-trace", "boot-tenant"));
            MDC.clear();
            tenant.remove();
            Snapshot next = executor.submit(() -> new Snapshot(MDC.get("traceId"), tenant.get()))
                    .get(5, TimeUnit.SECONDS);
            assertThat(next).isEqualTo(new Snapshot(null, null));
        });
    }

    @Test
    void shouldPreserveRegisteredBusinessDecorator() {
        runner.withUserConfiguration(BeanDecoratorConfiguration.class).run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            AtomicInteger calls = context.getBean(AtomicInteger.class);
            int before = calls.get();
            MDC.put("traceId", "bean-trace");
            tenant.set("bean-tenant");
            Snapshot result = executor.submit(() -> new Snapshot(MDC.get("traceId"), tenant.get()))
                    .get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(new Snapshot("bean-trace", "bean-tenant"));
            assertThat(calls.get()).isEqualTo(before + 1);
        });
    }

    @Test
    void shouldApplyMdcWithMonitorOnly() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AifuliAsyncTraceAutoConfiguration.class))
                .withUserConfiguration(ExplicitExecutorConfiguration.class).run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);
                    executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
                    MDC.put("traceId", "monitor-only");
                    assertThat(executor.submit(() -> MDC.get("traceId")).get(5, TimeUnit.SECONDS))
                            .isEqualTo("monitor-only");
                });
    }

    @Test
    void shouldKeepTtlAndExplicitDecoratorWhenTracingIsDisabled() {
        runner.withPropertyValues("aifuli.tracer.enable=false")
                .withUserConfiguration(ExplicitExecutorConfiguration.class).run(context -> {
                    assertThat(context).doesNotHaveBean(MdcTaskDecorator.class);
                    ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);
                    executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
                    AtomicInteger calls = context.getBean(AtomicInteger.class);
                    int before = calls.get();
                    MDC.put("traceId", "not-propagated");
                    tenant.set("ttl-only");
                    Snapshot result = executor.submit(() -> new Snapshot(MDC.get("traceId"), tenant.get()))
                            .get(5, TimeUnit.SECONDS);
                    assertThat(result).isEqualTo(new Snapshot(null, "ttl-only"));
                    assertThat(calls.get()).isEqualTo(before + 1);
                });
    }

    @Test
    void shouldComposeSimpleAsyncExecutor() {
        runner.withUserConfiguration(SimpleExecutorConfiguration.class).run(context -> {
            SimpleAsyncTaskExecutor executor = context.getBean(SimpleAsyncTaskExecutor.class);
            MDC.put("traceId", "simple-trace");
            tenant.set("simple-tenant");
            Snapshot result = executor.submit(() -> new Snapshot(MDC.get("traceId"), tenant.get()))
                    .get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(new Snapshot("simple-trace", "simple-tenant"));
            assertThat(context.getBean(AtomicInteger.class).get()).isEqualTo(1);
        });
    }

    @Test
    void shouldNotInstallSameDecoratorTwice() {
        runner.withUserConfiguration(BeanDecoratorConfiguration.class).run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);
            // 直接重放后处理器验证组合按实例去重，不依赖 Boot 当前初始化顺序。
            BeanPostProcessor processor = context.getBean("mdcTaskExecutorBeanPostProcessor", BeanPostProcessor.class);
            processor.postProcessBeforeInitialization(executor, "applicationTaskExecutor");
            processor.postProcessBeforeInitialization(executor, "applicationTaskExecutor");
            AtomicInteger calls = context.getBean(AtomicInteger.class);
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertThat(calls.get()).isEqualTo(1);
        });
    }

    @Test
    void shouldPropagateThroughAsyncProxy() {
        runner.withBean(AsyncReader.class, () -> new AsyncReader(tenant)).run(context -> {
            AsyncReader reader = context.getBean(AsyncReader.class);
            reader.read().get(5, TimeUnit.SECONDS);
            MDC.put("traceId", "async-proxy");
            tenant.set("async-tenant");
            assertThat(reader.read().get(5, TimeUnit.SECONDS))
                    .isEqualTo(new Snapshot("async-proxy", "async-tenant"));
        });
    }

    private record Snapshot(String traceId, String tenant) { }

    static class AsyncReader {
        private final TransmittableThreadLocal<String> tenant;

        AsyncReader(TransmittableThreadLocal<String> tenant) {
            this.tenant = tenant;
        }

        @Async
        public CompletableFuture<Snapshot> read() {
            return CompletableFuture.completedFuture(new Snapshot(MDC.get("traceId"), tenant.get()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BeanDecoratorConfiguration {
        @Bean
        AtomicInteger beanDecoratorCalls() {
            return new AtomicInteger();
        }

        @Bean
        TaskDecorator businessDecorator(AtomicInteger calls) {
            return task -> () -> {
                calls.incrementAndGet();
                task.run();
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SimpleExecutorConfiguration {
        @Bean
        AtomicInteger simpleDecoratorCalls() {
            return new AtomicInteger();
        }

        @Bean(destroyMethod = "close")
        SimpleAsyncTaskExecutor simpleExecutor(AtomicInteger calls) {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
            executor.setTaskDecorator(task -> () -> {
                calls.incrementAndGet();
                task.run();
            });
            return executor;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExplicitExecutorConfiguration {
        @Bean
        AtomicInteger decoratorCalls() {
            return new AtomicInteger();
        }

        @Bean
        ThreadPoolTaskExecutor customExecutor(AtomicInteger calls) {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(16);
            executor.setTaskDecorator(task -> () -> {
                calls.incrementAndGet();
                task.run();
            });
            return executor;
        }
    }
}
