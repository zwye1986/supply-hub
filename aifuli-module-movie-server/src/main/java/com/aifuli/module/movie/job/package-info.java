/**
 * 城市、影院、热映影片、待映影片、排期五类独立定时任务，随 XXL-JOB 执行器启动统一注册，启停和触发计划由调度中心管理。
 * 任务使用 XXL-JOB 执行器，业务处理委托给 service 层；全量初始化不注册定时任务。
 *
 * <p>设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复。</p>
 */
package com.aifuli.module.movie.job;
