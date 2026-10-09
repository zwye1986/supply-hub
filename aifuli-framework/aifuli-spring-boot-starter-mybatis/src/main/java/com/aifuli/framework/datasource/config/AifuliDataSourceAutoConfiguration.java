package com.aifuli.framework.datasource.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 数据库配置类
 *
 * <p>2026-10-09 去 Druid 迁移后不再注册任何 Druid 监控 Servlet / 过滤器，
 * 仅保留事务管理开启职责。数据源本身由 Spring Boot 默认自动配置（HikariCP）或
 * dynamic-datasource starter 装配。设计见
 * {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#53-监控端点广告过滤器与前端页面退出}。</p>
 *
 * @author 芋道源码
 */
@AutoConfiguration
@EnableTransactionManagement(proxyTargetClass = true) // 启动事务管理
public class AifuliDataSourceAutoConfiguration {

}
