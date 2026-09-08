package com.aifuli.module.supplychain.routing.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 路由轮次决策状态，对应 {@code sc_supplier_routing_round.decision_status}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum RoutingDecisionStatus {
    SELECTED((short) 10),
    NO_CANDIDATE((short) 20),
    CONFLICT((short) 30);

    private final short code;
}
