package com.aifuli.module.supplychain.routing.application;

/**
 * 管理端适用范围展示值。项目与商品类型本期固定 {@code ALL}。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md#34-响应字段}。
 */
public enum RoutingRuleScopeMode {
    ALL,
    SPECIFIED
}
