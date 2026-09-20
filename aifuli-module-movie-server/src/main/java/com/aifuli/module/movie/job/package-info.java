/**
 * 城市、影院、热映影片、待映影片、排期五类独立定时任务，通过 XXL-JOB 配置触发计划，应用开关控制 Handler 注册。
 * 任务使用 XXL-JOB 执行器，业务处理委托给 service 层；全量初始化不注册定时任务。
 *
 * <p>设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复。</p>
 */
package com.aifuli.module.movie.job;
