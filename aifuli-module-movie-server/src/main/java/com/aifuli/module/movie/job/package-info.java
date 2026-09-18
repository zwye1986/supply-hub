/**
 * 城市、影院、热映影片、待映影片、排期五类独立定时任务，分别配置触发计划与启停条件。
 * 任务使用电影专属调度器，业务处理委托给 service 层；全量初始化不注册定时任务。
 *
 * <p>设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复。</p>
 */
package com.aifuli.module.movie.job;
