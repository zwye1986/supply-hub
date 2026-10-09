package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;
import com.aifuli.module.supplychain.routing.domain.RoutingDecision;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.routing.domain.RoutingSortConfig;
import com.aifuli.module.supplychain.routing.domain.RoutingStrategy;
import com.aifuli.module.supplychain.routing.domain.SupplierRoutingEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实纯路由内核验证资格映射，不以重新挑选 CONFIRMED 候选改变首选语义。
 * 本测试不证明独立事务或当前数据库视图，见
 * docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#71-资格语义不变。
 */
class CurrentSupplyQualificationPolicyTest {

    @Test
    void selectedUnknownMustNotBeReplacedByLowerRankedConfirmedCandidate() {
        RoutingCandidate unknown = candidate(1L, "UNKNOWN", true, List.of(), 90);
        RoutingCandidate confirmed = candidate(2L, "CONFIRMED", true, List.of(), 60);
        RoutingDecision decision = route(List.of(confirmed, unknown));
        assertThat(decision.selectedCandidate()).isSameAs(unknown);

        var result = CurrentSupplyQualificationPolicy.evaluate(decision);

        assertThat(result.qualification()).isEqualTo(SupplyQualification.UNKNOWN);
        assertThat(result.confirmed()).isFalse();
        assertThat(result.routingDecision()).isSameAs(decision);
        assertThat(result.routingDecision().orderedCandidates()).containsExactly(unknown, confirmed);
        assertThat(result.reasonCodes()).containsExactly(decision.reasonCode());
    }

    @Test
    void selectedConfirmedCandidateIsTheOnlyPositiveAuthorization() {
        RoutingCandidate confirmed = candidate(1L, "CONFIRMED", true, List.of(), 90);
        RoutingDecision decision = route(List.of(confirmed));

        var result = CurrentSupplyQualificationPolicy.evaluate(decision);

        assertThat(result.qualification()).isEqualTo(SupplyQualification.CONFIRMED);
        assertThat(result.confirmed()).isTrue();
        assertThat(result.routingDecision().selectedCandidate()).isSameAs(confirmed);
        assertThat(result.reasonCodes()).containsExactly(decision.reasonCode());
    }

    @Test
    void qualificationReasonsAreImmutable() {
        var result = CurrentSupplyQualificationPolicy.evaluate(route(List.of(
                candidate(1L, "CONFIRMED", true, List.of(), 90))));

        assertThatThrownBy(() -> result.reasonCodes().add("MUTATED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INVENTORY_FACT_REQUIRED", "INVENTORY_FACT_STALE", "INVENTORY_FACT_UNKNOWN",
            "BALANCE_FACT_REQUIRED", "BALANCE_FACT_STALE", "BALANCE_FACT_UNKNOWN",
            "HEALTH_FACT_REQUIRED", "HEALTH_FACT_STALE", "HEALTH_FACT_UNKNOWN"
    })
    void allCandidatesRejectedOnlyForRequiredMissingFactsAreUnknown(String reason) {
        RoutingDecision decision = route(List.of(candidate(1L, "UNAVAILABLE", false,
                List.of(reason), 90)));
        assertThat(decision.selected()).isFalse();

        var result = CurrentSupplyQualificationPolicy.evaluate(decision);

        assertThat(result.qualification()).isEqualTo(SupplyQualification.UNKNOWN);
        assertThat(result.reasonCodes()).containsExactly(reason);
        assertThat(result.confirmed()).isFalse();
        assertThat(result.routingDecision()).isSameAs(decision);
    }

    @Test
    void requiredFactReasonsFromAllRejectedCandidatesAreRetainedInOriginalOrder() {
        RoutingDecision decision = route(List.of(
                candidate(2L, "UNAVAILABLE", false, List.of("BALANCE_FACT_STALE"), 60),
                candidate(1L, "UNAVAILABLE", false,
                        List.of("INVENTORY_FACT_REQUIRED", "BALANCE_FACT_STALE"), 90)));

        var result = CurrentSupplyQualificationPolicy.evaluate(decision);

        assertThat(result.qualification()).isEqualTo(SupplyQualification.UNKNOWN);
        assertThat(result.reasonCodes()).containsExactly("INVENTORY_FACT_REQUIRED", "BALANCE_FACT_STALE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INVENTORY_ERROR", "BALANCE_ERROR", "HEALTH_ERROR", "INVENTORY_UNAVAILABLE",
            "BALANCE_INSUFFICIENT", "SUPPLIER_NOT_ACTIVE", "NO_EFFECTIVE_OFFER",
            "DAILY_QUOTA_EXCEEDED", "INVENTORY_FACT_REQUIRED_UNRECOGNIZED"
    })
    void explicitFailureOrUnrecognizedReasonMustNotBecomeUnknown(String reason) {
        RoutingDecision decision = route(List.of(candidate(1L, "UNAVAILABLE", false,
                List.of(reason), 90)));

        var result = CurrentSupplyQualificationPolicy.evaluate(decision);

        assertThat(result.qualification()).isEqualTo(SupplyQualification.UNAVAILABLE);
        assertThat(result.reasonCodes()).containsExactly(reason);
        assertThat(result.confirmed()).isFalse();
    }

    @Test
    void mixedRequiredFactAndStructuralRejectionsRemainUnavailable() {
        RoutingDecision decision = route(List.of(
                candidate(1L, "UNAVAILABLE", false, List.of("INVENTORY_FACT_REQUIRED"), 90),
                candidate(2L, "UNAVAILABLE", false, List.of("SUPPLIER_NOT_ACTIVE"), 60)));

        var result = CurrentSupplyQualificationPolicy.evaluate(decision);

        assertThat(result.qualification()).isEqualTo(SupplyQualification.UNAVAILABLE);
        assertThat(result.reasonCodes()).containsExactly("INVENTORY_FACT_REQUIRED", "SUPPLIER_NOT_ACTIVE");
    }

    @Test
    void emptyCandidatePoolDoesNotPretendToHaveMissingRequiredFacts() {
        RoutingDecision decision = route(List.of());
        var result = CurrentSupplyQualificationPolicy.evaluate(decision);

        assertThat(result.qualification()).isEqualTo(SupplyQualification.UNAVAILABLE);
        assertThat(result.reasonCodes()).containsExactly(decision.reasonCode());
    }

    @Test
    void unknownAvailabilityClassIsTechnicalFailureNotPositiveAuthorization() {
        RoutingDecision decision = route(List.of(candidate(1L, "BROKEN", true, List.of(), 90)));

        assertThatThrownBy(() -> CurrentSupplyQualificationPolicy.evaluate(decision))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDecisionIsNotConvertedToBusinessUnavailability() {
        assertThatThrownBy(() -> CurrentSupplyQualificationPolicy.evaluate(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static RoutingDecision route(List<RoutingCandidate> candidates) {
        RoutingInput input = new RoutingInput(1L, 2L, 3L, "DIRECT_RECHARGE", 1,
                LocalDateTime.of(2026, 10, 9, 12, 0), Map.of());
        return new SupplierRoutingEngine().decide(input, candidates,
                new RoutingSortConfig(RoutingStrategy.STABLE, RoutingStrategy.PRICE,
                        RoutingStrategy.COMPOSITE, 40, 30, 30));
    }

    private static RoutingCandidate candidate(Long id, String availability, boolean eligible,
                                               List<String> reasons, int stabilityScore) {
        return new RoutingCandidate(0, id, "SUPPLIER_" + id, "供应商" + id,
                id, id, id, id, 100L, "CNY", stabilityScore, 80, eligible,
                availability, reasons, "NOT_CAPTURED", "NOT_CHECKED", "NOT_CHECKED", null);
    }
}
