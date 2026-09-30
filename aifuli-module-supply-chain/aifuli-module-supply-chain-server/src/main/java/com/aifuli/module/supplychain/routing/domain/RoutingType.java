package com.aifuli.module.supplychain.routing.domain;

/**
 * 供应商路由类型，对应策略与路由轮次 {@code routing_type}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public enum RoutingType {
    STANDARD_PRODUCT,
    SCENARIO;

    public String code() {
        return name();
    }
}
