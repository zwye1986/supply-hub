package com.aifuli.framework.datasource.core.filter;

/**
 * 已作废（Druid 组件已完全移除，/druid 监控端点整体下线）。
 *
 * @deprecated 原实现依赖 {@code com.alibaba.druid.util.Utils}，已随 Druid 依赖移除而作废，严禁注册或调用。
 * 原实现已归档于 {@code docs/superpowers/specs/archive/deprecated-druid/DruidAdRemoveFilter.java}。
 */
@Deprecated(since = "2026-10-09", forRemoval = true)
public class DruidAdRemoveFilter {

    public static final String DEPRECATION_NOTICE =
            "该类已作废：Druid 组件已完全移除，/druid 监控端点整体下线，严禁调用。"
            + "详见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md";

    public DruidAdRemoveFilter() {
        throw new UnsupportedOperationException(DEPRECATION_NOTICE);
    }
}
