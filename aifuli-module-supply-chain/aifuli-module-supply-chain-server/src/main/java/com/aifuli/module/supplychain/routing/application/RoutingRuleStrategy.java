package com.aifuli.module.supplychain.routing.application;

/**
 * 管理端优先级占位。仅 {@code STABLE} 与引擎一致，其余两项只为页面三列回显。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md#34-响应字段}。
 */
public enum RoutingRuleStrategy {
    STABLE,
    PRICE,
    COMPOSITE
}
