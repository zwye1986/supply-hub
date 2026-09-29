package com.aifuli.module.movie.framework.supply;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 三渠道共用的平台停售策略，每次对外查询从可刷新的 Environment 取得配置快照。
 * 不缓存配置值，Nacos 刷新后下次查询生效；非法值使查询失败，不影响排期采集。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#83-排期映射与统一动态停售规则
 */
@Component
@RequiredArgsConstructor
public class MovieSalePolicy {
    /** 统一提前停售时长的动态配置键。 */
    private static final String CONFIG_KEY = "movie.supply.stop-sell-before-start";
    /** 用户确认的默认停售提前量，单位分钟。 */
    private static final String DEFAULT_LEAD_TIME = "45m";
    /** 读取当前配置源，不依赖账号配置对象是否重新绑定。 */
    private final Environment environment;

    /** 每次在售查询调用一次，同一次查询的过滤和返回停售时间复用该值。 */
    public Duration snapshot() {
        String value = environment.getProperty(CONFIG_KEY, DEFAULT_LEAD_TIME);
        final Duration leadTime;
        try {
            leadTime = DurationStyle.detectAndParse(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(CONFIG_KEY + " 必须为有效时长，例如 45m", exception);
        }
        if (leadTime.isNegative()) {
            throw new IllegalArgumentException(CONFIG_KEY + " 不得为负数");
        }
        return leadTime;
    }
}
