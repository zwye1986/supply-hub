package com.aifuli.module.movie.framework.supply;

import com.xxl.job.core.context.XxlJobHelper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 电影 XXL-JOB 执行边界，承接失败指标和安全诊断，不参与业务编排或类别锁。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复
 */
@Component
@ConditionalOnProperty(prefix = "movie.supply.sync", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class MovieSyncJobExecutor {
    /** 沿用原调度器的失败指标，避免迁移后丢失监控。 */
    private final MeterRegistry meterRegistry;

    /**
     * 每轮委托一个业务入口；失败明确回报调度中心，原始异常消息不进入第三方任务日志。
     * handlerName 为固定 Handler 名；task 为持有原有类别锁的同步服务入口。
     */
    public void execute(String handlerName, Runnable task) {
        log.info("电影同步任务开始 handler={} jobId={}", handlerName, XxlJobHelper.getJobId());
        try {
            task.run();
        } catch (Exception failure) {
            meterRegistry.counter("movie.supply.sync.task.failures").increment();
            // 只保留有界诊断，异常 message 可能包含供应商凭证、URL 或原始响应。
            List<String> causes = new ArrayList<>();
            Throwable current = failure;
            while (current != null && causes.size() < 10) {
                String location = current.getStackTrace().length == 0 ? "unknown" : current.getStackTrace()[0].toString();
                String code = current instanceof SQLException sql ? "/sqlCode=" + sql.getErrorCode() : "";
                causes.add(current.getClass().getSimpleName() + code + "@" + location);
                current = current.getCause();
            }
            String message = "电影同步任务失败 handler=" + handlerName + "，安全因果链=" + causes;
            log.error("{}", message);
            // XXL-JOB 按失败状态结束本轮；脱离调度上下文调用时必须抛回原始异常。
            if (!XxlJobHelper.handleFail(message)) {
                throw failure;
            }
        }
    }
}
