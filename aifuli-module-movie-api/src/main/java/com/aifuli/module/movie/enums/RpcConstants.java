package com.aifuli.module.movie.enums;

/**
 * 电影 RPC 的稳定寻址约定，供商城与供应链中台的 Feign 契约引用。
 *
 * <p>电影服务独立注册，不使用商城聚合服务名。
 * 设计：docs/电影子系统与中台履约详细设计.md#51-工程初始化落地约定2026-09-07</p>
 */
public final class RpcConstants {

    /** 独立电影应用的服务注册名称，与 spring.application.name 保持一致。 */
    public static final String NAME = "movie-server";

    /** 系统间电影领域接口前缀，商城 C 端接口由商城应用提供。 */
    public static final String PREFIX = "/rpc-api/movie";

    /** 常量类不允许实例化。 */
    private RpcConstants() {
    }
}
