package com.aifuli.module.supplychain.tradecore.catalog.domain;

import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService.RoutingSnapshot;
import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 当前一致性视图内的不可变资格结果，保留真实路由快照供最终受理直接持久化。
 * 首选 UNKNOWN 不得改选后备；REQUIRED 事实缺失不等于明确缺货。
 * 设计见 {@code docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#5-当前供给reader与错误分类}。
 */
public record CurrentSupplyEligibility(ListingAvailability availability, String reason,
                                       LocalDateTime evaluatedAt, RoutingSnapshot routingSnapshot,
                                       LocalDateTime nextBoundaryAt) {
    public CurrentSupplyEligibility(ListingAvailability availability, String reason,
                                   LocalDateTime evaluatedAt, RoutingSnapshot routingSnapshot) {
        this(availability, reason, evaluatedAt, routingSnapshot, null);
    }
    private static final String CONFIRMED = "CONFIRMED";
    private static final String SELECTED_UNKNOWN = "SELECTED_SUPPLY_UNKNOWN";
    private static final Set<String> UNKNOWN_FACT_REASONS = Set.of(
            "INVENTORY_FACT_REQUIRED", "INVENTORY_FACT_STALE", "INVENTORY_FACT_UNKNOWN",
            "BALANCE_FACT_REQUIRED", "BALANCE_FACT_STALE", "BALANCE_FACT_UNKNOWN",
            "HEALTH_FACT_REQUIRED", "HEALTH_FACT_STALE", "HEALTH_FACT_UNKNOWN");

    public boolean confirmed() {
        return availability == ListingAvailability.AVAILABLE;
    }

    public static CurrentSupplyEligibility fromRouting(RoutingSnapshot snapshot, LocalDateTime at) {
        RoutingCandidate selected = snapshot.decision().selectedCandidate();
        if (selected != null) {
            return new CurrentSupplyEligibility(CONFIRMED.equals(selected.availabilityClass())
                    ? ListingAvailability.AVAILABLE : ListingAvailability.UNKNOWN,
                    CONFIRMED.equals(selected.availabilityClass()) ? CONFIRMED : SELECTED_UNKNOWN, at, snapshot);
        }
        // 有某个仅因未知事实被淘汰的候选时保留 UNKNOWN；同时停用/超配额等明确拒绝不被未知事实覆盖。
        boolean unknown = snapshot.decision().orderedCandidates().stream().anyMatch(candidate ->
                !candidate.rejectionReasons().isEmpty()
                        && candidate.rejectionReasons().stream().allMatch(UNKNOWN_FACT_REASONS::contains));
        String reason = snapshot.decision().orderedCandidates().stream()
                .flatMap(candidate -> candidate.rejectionReasons().stream())
                .findFirst().orElse(snapshot.decision().reasonCode());
        return new CurrentSupplyEligibility(unknown ? ListingAvailability.UNKNOWN : ListingAvailability.UNAVAILABLE,
                reason, at, snapshot);
    }
}
