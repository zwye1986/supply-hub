package com.aifuli.module.supplychain.routing.domain;

import java.util.EnumSet;
import java.util.Objects;

/**
 * 命中规则的排序配置：三个优先级位次（不可重复、覆盖全部策略）+ 全局共享综合权重（和恒为100）。
 *
 * <p>由 application 层从已发布规则与策略行读出后注入，保持 domain 对持久化无依赖。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#54-候选解析与三策略排序}。</p>
 */
public record RoutingSortConfig(RoutingStrategy first, RoutingStrategy second, RoutingStrategy third,
                                int stabilityWeight, int priceWeight, int timelinessWeight) {

    /** 综合权重上限，与 sc_supplier_routing_policy DDL CHECK 一致。 */
    public static final int WEIGHT_SUM = 100;

    public RoutingSortConfig {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(third, "third");
        if (first == second || first == third || second == third
                || !EnumSet.allOf(RoutingStrategy.class).equals(EnumSet.of(first, second, third))) {
            throw new IllegalArgumentException("Routing strategies must be a permutation of STABLE/PRICE/COMPOSITE");
        }
        if (stabilityWeight < 0 || priceWeight < 0 || timelinessWeight < 0
                || stabilityWeight + priceWeight + timelinessWeight != WEIGHT_SUM) {
            throw new IllegalArgumentException("Routing composite weights must sum to 100");
        }
    }
}
