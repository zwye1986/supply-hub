package com.aifuli.module.supplychain.routing.domain;

/**
 * 路由候选记录决策原因码（选中 / 合格未选）；淘汰原因仍可来自引擎动态列表。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public enum RoutingRecordDecisionCode {
    SELECTED,
    ELIGIBLE_NOT_SELECTED
}
