package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.routing.domain.RoutingDecision;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 只映射真实路由决策的资格，不重新排序、挑选候选或访问数据库。
 * 调用方负责在独立当前视图产生决策；本类本身不证明数据库事实仍然有效。
 * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#71-资格语义不变。
 */
public final class CurrentSupplyQualificationPolicy {

    /** 仅这九类 REQUIRED 事实等待原因可将全部淘汰结果解释为 UNKNOWN；ERROR 不在其中。 */
    private static final Set<String> REQUIRED_FACT_WAITING_REASONS = Set.of(
            "INVENTORY_FACT_REQUIRED", "INVENTORY_FACT_STALE", "INVENTORY_FACT_UNKNOWN",
            "BALANCE_FACT_REQUIRED", "BALANCE_FACT_STALE", "BALANCE_FACT_UNKNOWN",
            "HEALTH_FACT_REQUIRED", "HEALTH_FACT_STALE", "HEALTH_FACT_UNKNOWN");

    private CurrentSupplyQualificationPolicy() {
    }

    /**
     * 保留真实首选；UNKNOWN 首选不能跳过以选择后备 CONFIRMED。
     * 无首选时，只有非空候选池的所有淘汰原因均为 REQUIRED 事实等待才返回 UNKNOWN，
     * 混合结构拒绝或 ERROR 保持 UNAVAILABLE。损坏决策不转换为业务等待。
     * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#71-资格语义不变。
     */
    public static Result evaluate(RoutingDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (decision.selected()) {
            SupplyQualification qualification = SupplyQualification.valueOf(
                    decision.selectedCandidate().availabilityClass());
            return new Result(qualification, decision, List.of(decision.reasonCode()));
        }
        List<String> reasons = decision.orderedCandidates().stream()
                .flatMap(candidate -> candidate.rejectionReasons().stream())
                .distinct().toList();
        boolean allWaitingForRequiredFacts = !reasons.isEmpty()
                && reasons.stream().allMatch(REQUIRED_FACT_WAITING_REASONS::contains);
        return new Result(allWaitingForRequiredFacts ? SupplyQualification.UNKNOWN
                : SupplyQualification.UNAVAILABLE, decision,
                reasons.isEmpty() ? List.of(decision.reasonCode()) : reasons);
    }

    /** 不可变映射结果，保留原决策供后续完整路由快照持久化使用。 */
    public record Result(SupplyQualification qualification, RoutingDecision routingDecision,
                         List<String> reasonCodes) {
        public Result {
            Objects.requireNonNull(qualification, "qualification");
            Objects.requireNonNull(routingDecision, "routingDecision");
            reasonCodes = List.copyOf(reasonCodes);
        }

        public boolean confirmed() {
            return qualification == SupplyQualification.CONFIRMED;
        }
    }
}
