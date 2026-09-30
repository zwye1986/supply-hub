package com.aifuli.module.movie.enums;

/** 平台主数据状态，与六张电影表的 DDL COMMENT 保持一致。 */
public final class MovieDataStatus {
    /** 标准数据启用或渠道影院可售。 */
    public static final int ENABLED = 1;
    /** 标准数据禁用或渠道影院不可售。 */
    public static final int DISABLED = 0;
    /** 热映影片。 */
    public static final int NOW_SHOWING = 0;
    /** 预售待映影片。 */
    public static final int PRESELL = 1;

    private MovieDataStatus() {
    }
}
