package com.aifuli.module.movie.job;

import com.aifuli.module.movie.service.sync.MovieSupplySyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import static com.aifuli.module.movie.framework.supply.MovieSupplySchedulingConfiguration.SCHEDULER_NAME;

/**
 * 待映影片独立同步，默认每日北京时间 02:20。
 * 任务总开关和本任务开关均开启时才注册；数据处理委托同步服务。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复
 */
@Component
@ConditionalOnProperty(prefix = "movie.supply.sync", name = {"enabled", "soon-film.enabled"}, havingValue = "true")
@RequiredArgsConstructor
public class MovieSoonFilmSyncJob {
    /** 本任务唯一的业务依赖，不在调度层访问供应商或数据表。 */
    private final MovieSupplySyncService syncService;

    /** 待映影片独立同步，默认每日北京时间 02:20。 */
    @Scheduled(cron = "${movie.supply.sync.soon-film.cron:0 20 2 * * *}",
            zone = "${movie.supply.sync.zone:Asia/Shanghai}", scheduler = SCHEDULER_NAME)
    public void synchronizeSoonFilms() {
        syncService.synchronizeSoonFilms();
    }
}
