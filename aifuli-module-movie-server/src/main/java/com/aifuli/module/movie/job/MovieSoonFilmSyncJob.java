package com.aifuli.module.movie.job;

import com.aifuli.module.movie.framework.supply.MovieSyncJobExecutor;
import com.aifuli.module.movie.service.sync.MovieSupplySyncService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 待映影片独立同步，执行计划由 XXL-JOB 调度中心管理。
 * XXL-JOB 执行器启动时统一注册；任务启停和执行时间由调度中心管理，数据处理委托同步服务。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复
 */
@Component
@RequiredArgsConstructor
public class MovieSoonFilmSyncJob {
    /** 调度中心使用的稳定 Handler 标识。 */
    private static final String HANDLER_NAME = "movieSoonFilmSyncJob";

    /** 本任务唯一的业务依赖，不在调度层访问供应商或数据表。 */
    private final MovieSupplySyncService syncService;
    /** 统一处理 XXL-JOB 失败回报与安全日志。 */
    private final MovieSyncJobExecutor jobExecutor;

    /** 待映影片独立同步，执行计划由 XXL-JOB 调度中心管理。 */
    @XxlJob(HANDLER_NAME)
    public void synchronizeSoonFilms() {
        jobExecutor.execute(HANDLER_NAME, syncService::synchronizeSoonFilms);
    }
}
