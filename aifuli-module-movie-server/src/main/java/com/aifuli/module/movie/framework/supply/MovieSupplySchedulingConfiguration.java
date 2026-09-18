package com.aifuli.module.movie.framework.supply;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * 电影独立调度器；五类任务不争用唯一线程，跨实例互斥由各类别业务锁保障。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
@RequiredArgsConstructor
@EnableScheduling
@ConditionalOnProperty(prefix = "movie.supply.sync", name = "enabled", havingValue = "true")
public class MovieSupplySchedulingConfiguration {
    /** 任务失败计数进入现有监控注册表，不因日志脱敏而报假成功。 */
    private final MeterRegistry meterRegistry;
    /** 专属调度器 Bean 名，Job 显式引用，避免受到其它调度器候选影响。 */
    public static final String SCHEDULER_NAME = "movieSupplyTaskScheduler";
    /** 每类任务一个执行槽，外部影院请求本身不并发。 */
    private static final int TASK_THREADS = 5;

    /** 由容器管理线程池生命周期，总开关关闭时不创建调度线程。 */
    @Bean(name = SCHEDULER_NAME)
    public ThreadPoolTaskScheduler movieSupplyTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(TASK_THREADS);
        scheduler.setThreadNamePrefix("movie-supply-job-");
        scheduler.setRemoveOnCancelPolicy(true);
        // 异常仍由服务抛出并保留因果链；定时器只输出类型、错误码及栈位置，避免上游报文随异常泄露。
        scheduler.setErrorHandler(this::reportFailure);
        return scheduler;
    }

    /** 有界诊断不输出 Throwable.message，Jackson/HTTP/SQL 原始异常可能携带报文、URL 或凭证。 */
    private void reportFailure(Throwable failure) {
        meterRegistry.counter("movie.supply.sync.task.failures").increment();
        List<String> causes = new ArrayList<>();
        Throwable current = failure;
        while (current != null && causes.size() < 10) {
            String location = current.getStackTrace().length == 0 ? "unknown" : current.getStackTrace()[0].toString();
            String code = current instanceof java.sql.SQLException sql ? "/sqlCode=" + sql.getErrorCode() : "";
            causes.add(current.getClass().getSimpleName() + code + "@" + location);
            current = current.getCause();
        }
        log.error("电影同步任务失败，按对应任务重跑；安全因果链={}", causes);
    }
}
