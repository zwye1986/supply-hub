package com.aifuli.module.movie.dal.dataobject.mongo;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

/**
 * 排期持久化结构约定，不承担供应商适配、领域转换或数据库调用。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#72-锁快照与并发边界
 */
public final class CinemaScheduleDocument {
    /** 独立电影库的排期集合。 */
    public static final String COLLECTION = "cinema_schedule";
    /** 单影院刷新版本集合，由部署脚本创建。 */
    public static final String REFRESH_COLLECTION = "cinema_schedule_refresh";
    /** 包含影院 ID 的排期键版本，读取时排除旧格式。 */
    public static final int SCHEMA_VERSION = 2;

    private CinemaScheduleDocument() {
    }

    /** 各分量独立编码，原始 ID 即使包含冒号也不能跨影院或账号碰撞。 */
    public static String key(String... parts) {
        return "v" + SCHEMA_VERSION + ":" + Arrays.stream(parts)
                .map(part -> Base64.getUrlEncoder().withoutPadding().encodeToString(part.getBytes(StandardCharsets.UTF_8)))
                .collect(Collectors.joining(":"));
    }
}
