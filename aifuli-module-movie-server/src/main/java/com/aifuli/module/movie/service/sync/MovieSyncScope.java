package com.aifuli.module.movie.service.sync;

/**
 * 同步数据所有权对应的互斥范围；热映与待映共享 FILM，排期使用独立范围。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复
 */
public enum MovieSyncScope {
    /** 标准城市与渠道城市映射。 */
    CITY,
    /** 标准影院与渠道影院映射。 */
    CINEMA,
    /** 热映、待映影片及其映射，共同维护同一标准影片表。 */
    FILM,
    /** 所有账号影院的排期快照，防止两个轮次相互清理。 */
    SCHEDULE
}
