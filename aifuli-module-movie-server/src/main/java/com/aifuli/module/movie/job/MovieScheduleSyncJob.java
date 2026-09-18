package com.aifuli.module.movie.job;

import com.aifuli.module.movie.service.sync.MovieSupplySyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import static com.aifuli.module.movie.framework.supply.MovieSupplySchedulingConfiguration.SCHEDULER_NAME;

/**
 * 排期独立同步，每轮完成后按配置等待，不承诺全国刷新耗时。
 * 任务总开关和本任务开关均开启时才注册；数据处理委托同步服务。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复
 */
@Component
@ConditionalOnProperty(prefix = "movie.supply.sync", name = {"enabled", "schedule.enabled"}, havingValue = "true")
@RequiredArgsConstructor
public class MovieScheduleSyncJob {
    /** 本任务唯一的业务依赖，不在调度层访问供应商或数据表。 */
    private final MovieSupplySyncService syncService;

    /** 排期独立同步，每轮完成后按配置等待，不承诺全国刷新耗时。 */
    @Scheduled(fixedDelayString = "${movie.supply.sync.schedule.delay:PT10M}",
            initialDelayString = "${movie.supply.sync.schedule.initial-delay:PT1M}", scheduler = SCHEDULER_NAME)
    public void synchronizeSchedules() {
        syncService.synchronizeSchedules();
    }
}
