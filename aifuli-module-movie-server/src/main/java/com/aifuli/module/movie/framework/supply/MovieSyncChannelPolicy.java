package com.aifuli.module.movie.framework.supply;

import com.aifuli.module.supplychain.api.movie.MovieSupplierChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每轮从动态Environment取得渠道选择快照，缺省关闭所有渠道，未知值失败关闭。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#数据库账号与渠道选择落地2026-09-29
 */
@Component
@RequiredArgsConstructor
public class MovieSyncChannelPolicy {
    /** 使用单一逗号分隔字符串，清空后不会残留旧列表下标属性。 */
    private static final String CONFIG_KEY = "movie.supply.enabled-channels";
    /** 不缓存配置值，Nacos更新在下次同步入口生效。 */
    private final Environment environment;

    /** 空配置跳过全部渠道；大小写统一按协议枚举填写，不静默丢弃错误配置。 */
    public List<MovieSupplierChannel> snapshot() {
        return MovieSupplierChannel.parseSelection(environment.getProperty(CONFIG_KEY, ""));
    }
}
