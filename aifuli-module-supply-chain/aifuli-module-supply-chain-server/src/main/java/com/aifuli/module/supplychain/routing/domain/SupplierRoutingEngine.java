package com.aifuli.module.supplychain.routing.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 无 Spring、数据库、网络、缓存和系统时钟依赖的确定性路由内核。 */
public final class SupplierRoutingEngine {

    private static final Comparator<RoutingCandidate> M1_ORDER =
            Comparator.comparingInt(RoutingCandidate::candidatePriority)
                    .thenComparing(RoutingCandidate::supplierCode)
                    .thenComparing(RoutingCandidate::offerId,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    public RoutingDecision decide(RoutingInput input, List<RoutingCandidate> candidates) {
        if (input == null || candidates == null) {
            throw new IllegalArgumentException("Routing input and candidates are required");
        }
        List<RoutingCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(M1_ORDER);
        return ordered.stream()
                .filter(RoutingCandidate::eligible)
                .findFirst()
                .map(selected -> new RoutingDecision(selected, ordered, "SELECTED_BY_STABLE_PRIORITY"))
                .orElseGet(() -> new RoutingDecision(null, ordered, "NO_ELIGIBLE_CANDIDATE"));
    }
}
