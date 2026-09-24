package com.aifuli.module.supplychain.routing.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 无 Spring、数据库、网络、缓存和系统时钟依赖的确定性路由内核。
 *
 * <p>PRD 模型下按 {@link RoutingSortConfig} 的三个优先级位次逐级比较候选：
 * STABLE 用稳定性评分降序、PRICE 用冻结供货价升序、COMPOSITE 用全局权重合成综合分降序；
 * 全部级平后按 candidatePriority → supplierCode → offerId 稳定裁决。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#54-候选解析与三策略排序}。</p>
 */
public final class SupplierRoutingEngine {

    private static final Comparator<RoutingCandidate> STABLE_TIE_BREAK =
            Comparator.comparingInt(RoutingCandidate::candidatePriority)
                    .thenComparing(RoutingCandidate::supplierCode)
                    .thenComparing(RoutingCandidate::offerId,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    public RoutingDecision decide(RoutingInput input, List<RoutingCandidate> candidates,
                                  RoutingSortConfig sortConfig) {
        if (input == null || candidates == null) {
            throw new IllegalArgumentException("Routing input and candidates are required");
        }
        Objects.requireNonNull(sortConfig, "sortConfig");
        List<RoutingCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(comparator(sortConfig, ordered));
        String reasonCode = "SELECTED_BY_STRATEGY_" + sortConfig.first() + "_"
                + sortConfig.second() + "_" + sortConfig.third();
        return ordered.stream()
                .filter(RoutingCandidate::eligible)
                .findFirst()
                .map(selected -> new RoutingDecision(selected, ordered, reasonCode))
                .orElseGet(() -> new RoutingDecision(null, ordered, "NO_ELIGIBLE_CANDIDATE"));
    }

    /** 按三优先级位次构造候选比较器；COMPOSITE 需先基于候选集价格极值归一化。 */
    private static Comparator<RoutingCandidate> comparator(RoutingSortConfig config,
                                                           List<RoutingCandidate> candidates) {
        long minPrice = Long.MAX_VALUE;
        long maxPrice = Long.MIN_VALUE;
        for (RoutingCandidate candidate : candidates) {
            if (candidate.eligible() && candidate.supplierSettlementUnitPrice() != null) {
                minPrice = Math.min(minPrice, candidate.supplierSettlementUnitPrice());
                maxPrice = Math.max(maxPrice, candidate.supplierSettlementUnitPrice());
            }
        }
        final long min = minPrice;
        final long span = Math.max(1, maxPrice - minPrice);
        Comparator<RoutingCandidate> first = strategyComparator(config.first(), config, min, span);
        Comparator<RoutingCandidate> second = strategyComparator(config.second(), config, min, span);
        Comparator<RoutingCandidate> third = strategyComparator(config.third(), config, min, span);
        return first.thenComparing(second).thenComparing(third).thenComparing(STABLE_TIE_BREAK);
    }

    private static Comparator<RoutingCandidate> strategyComparator(RoutingStrategy strategy,
                                                                   RoutingSortConfig config,
                                                                   long minPrice, long priceSpan) {
        return switch (strategy) {
            case STABLE -> Comparator.comparing(RoutingCandidate::stabilityScore,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case PRICE -> Comparator.comparing(RoutingCandidate::supplierSettlementUnitPrice,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case COMPOSITE -> Comparator
                    .comparingLong((RoutingCandidate candidate) ->
                            compositeScore(candidate, config, minPrice, priceSpan))
                    .reversed();
        };
    }

    /**
     * 综合分 = 稳定性权重×稳定性分 + 价格权重×归一化价格分 + 时效权重×时效分。
     * 价格分 = (maxPrice−price)×100/(maxPrice−minPrice)，候选集价格全部相同时记满分100；
     * 评分或价格缺失（被淘汰候选不参与选择，仅排序占位）按 0 计，保证确定性。
     */
    static long compositeScore(RoutingCandidate candidate, RoutingSortConfig config,
                               long minPrice, long priceSpan) {
        long stability = candidate.stabilityScore() == null ? 0L : candidate.stabilityScore();
        long timeliness = candidate.timelinessScore() == null ? 0L : candidate.timelinessScore();
        Long price = candidate.supplierSettlementUnitPrice();
        long priceScore;
        if (price == null) {
            priceScore = 0L;
        } else if (priceSpan <= 0) {
            priceScore = 100L;
        } else {
            long diff = Math.subtractExact(price, minPrice);
            priceScore = diff >= priceSpan ? 0L
                    : Math.subtractExact(priceSpan, diff) * 100L / priceSpan;
        }
        return config.stabilityWeight() * stability
                + config.timelinessWeight() * timeliness
                + config.priceWeight() * priceScore;
    }
}
