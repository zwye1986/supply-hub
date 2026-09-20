package com.aifuli.module.movie.job;

import com.aifuli.module.movie.framework.supply.MovieSyncJobExecutor;
import com.aifuli.module.movie.service.sync.MovieSupplySyncService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 热映影片独立同步，执行计划由 XXL-JOB 调度中心管理。
 * 任务总开关和本任务开关均开启时才注册；数据处理委托同步服务。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复
 */
@Component
@ConditionalOnProperty(prefix = "movie.supply.sync", name = {"enabled", "hot-film.enabled"}, havingValue = "true")
@RequiredArgsConstructor
public class MovieHotFilmSyncJob {
    /** 调度中心使用的稳定 Handler 标识。 */
    private static final String HANDLER_NAME = "movieHotFilmSyncJob";

    /** 本任务唯一的业务依赖，不在调度层访问供应商或数据表。 */
    private final MovieSupplySyncService syncService;
    /** 统一处理 XXL-JOB 失败回报与安全日志。 */
    private final MovieSyncJobExecutor jobExecutor;

    /** 热映影片独立同步，执行计划由 XXL-JOB 调度中心管理。 */
    @XxlJob(HANDLER_NAME)
    public void synchronizeHotFilms() {
        jobExecutor.execute(HANDLER_NAME, syncService::synchronizeHotFilms);
    }
}
