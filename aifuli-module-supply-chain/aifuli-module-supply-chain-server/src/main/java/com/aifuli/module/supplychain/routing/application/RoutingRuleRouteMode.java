package com.aifuli.module.supplychain.routing.application;

/**
 * 管理端路由模式展示值：1 个候选为固定，不少于 2 个为自动。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md#34-响应字段}。
 */
public enum RoutingRuleRouteMode {
    AUTO,
    FIXED
}
