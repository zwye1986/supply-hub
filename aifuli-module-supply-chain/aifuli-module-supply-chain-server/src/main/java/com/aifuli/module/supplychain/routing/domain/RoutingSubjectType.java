package com.aifuli.module.supplychain.routing.domain;

/**
 * 路由主体类型，对应 {@code sc_supplier_routing_round.routing_subject_type}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public enum RoutingSubjectType {
    STANDARD_REQUEST,
    SCENARIO_REQUEST;

    public String code() {
        return name();
    }
}
