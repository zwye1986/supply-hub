/**
 * 电影主数据与动态排期同步。供应商调用需记录脱敏摘要和耗时，高频日志须采样或聚合。
 *
 * <p>提供主数据幂等同步、账号影院排期快照与跨实例互斥，由 movie.job 包的任务入口调用。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复。</p>
 */
package com.aifuli.module.movie.service.sync;
