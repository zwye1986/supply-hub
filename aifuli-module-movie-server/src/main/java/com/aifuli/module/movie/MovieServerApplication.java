package com.aifuli.module.movie;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 电影专业子系统的独立启动入口，负责电影供给与供应商协议执行。
 *
 * <p>组件扫描仅覆盖当前电影包，不扫描商城或供应链中台的实现，
 * 不共享其它服务的本地事务。平台主数据不使用商城租户拦截。
 * 设计：docs/business/电影子系统与中台履约详细设计.md#51-工程初始化落地约定2026-09-07</p>
 */
@SpringBootApplication
public class MovieServerApplication {

    /** 启动日志仅记录生命周期，不输出可能包含凭证的启动参数。 */
    private static final Logger LOG = LoggerFactory.getLogger(MovieServerApplication.class);

    /**
     * 启动电影服务；基础设施连接信息由环境变量或电影专属配置注入。
     *
     * @param args Spring Boot 启动参数，禁止将其整体写入日志
     */
    public static void main(String[] args) {
        LOG.info("[main][开始启动电影专业子系统]");
        SpringApplication.run(MovieServerApplication.class, args);
    }
}
