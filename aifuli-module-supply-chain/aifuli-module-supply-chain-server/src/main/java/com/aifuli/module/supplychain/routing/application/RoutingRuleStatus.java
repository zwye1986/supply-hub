package com.aifuli.module.supplychain.routing.application;

/**
 * 管理端路由规则启停展示值。当前生效规则恒为 {@code ENABLED}。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md#34-响应字段}。
 */
public enum RoutingRuleStatus {
    ENABLED,
    DISABLED
}
