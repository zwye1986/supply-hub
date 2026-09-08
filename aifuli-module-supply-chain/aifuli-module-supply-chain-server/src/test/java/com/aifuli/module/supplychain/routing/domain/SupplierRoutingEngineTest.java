package com.aifuli.module.supplychain.routing.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 纯 {@link SupplierRoutingEngine} 单测：稳定排序、资格过滤与一期禁止动态改序。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#8.8-错误、指标与测试}。</p>
 */
class SupplierRoutingEngineTest {

    private static final RoutingInput INPUT = new RoutingInput(
            1L, 2L, "DIRECT_RECHARGE", 1L,
            LocalDateTime.of(2026, 8, 11, 10, 0), Map.of());

    private final SupplierRoutingEngine engine = new SupplierRoutingEngine();

    @Test
    void rejectsNullInputOrCandidates() {
        List<RoutingCandidate> candidates = List.of(eligible(100, "QNM", 10L, 900L));
        assertThatThrownBy(() -> engine.decide(null, candidates))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.decide(INPUT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectsByStablePrioritySupplierCodeAndOfferIdAfterShuffle() {
        RoutingCandidate primary = eligible(100, "QINAMENG", 20L, 900L);
        RoutingCandidate fallback = eligible(200, "ZETA", 30L, 100L);
        List<RoutingCandidate> shuffled = new ArrayList<>(List.of(fallback, primary));
        Random random = new Random(20260813L);
        for (int attempt = 0; attempt < 100; attempt++) {
            Collections.shuffle(shuffled, random);
            RoutingDecision decision = engine.decide(INPUT, shuffled);
            assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("QINAMENG");
            assertThat(decision.reasonCode()).isEqualTo("SELECTED_BY_STABLE_PRIORITY");
            assertThat(decision.orderedCandidates())
                    .extracting(RoutingCandidate::supplierCode)
                    .containsExactly("QINAMENG", "ZETA");
        }
    }

    @Test
    void breaksTiesBySupplierCodeThenOfferId() {
        RoutingCandidate beta = eligible(100, "BETA", 20L, 500L);
        RoutingCandidate alpha = eligible(100, "ALPHA", 30L, 900L);
        RoutingCandidate alphaEarlierOffer = eligible(100, "ALPHA", 10L, 100L);

        RoutingDecision decision = engine.decide(INPUT, List.of(beta, alphaEarlierOffer, alpha));
        assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("ALPHA");
        assertThat(decision.selectedCandidate().offerId()).isEqualTo(10L);
        assertThat(decision.orderedCandidates())
                .extracting(RoutingCandidate::supplierCode, RoutingCandidate::offerId)
                .containsExactly(
                        tuple("ALPHA", 10L),
                        tuple("ALPHA", 30L),
                        tuple("BETA", 20L));
    }

    @Test
    void doesNotLetSettlementPriceOverridePriorityOrder() {
        RoutingCandidate cheapHighPriority = eligible(100, "CHEAP", 10L, 100L);
        RoutingCandidate expensiveLowPriority = eligible(200, "EXPENSIVE", 20L, 9_999L);

        RoutingDecision decision = engine.decide(INPUT, List.of(expensiveLowPriority, cheapHighPriority));
        assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("CHEAP");
        assertThat(decision.orderedCandidates().getFirst().supplierSettlementUnitPrice()).isEqualTo(100L);
        assertThat(decision.orderedCandidates().get(1).supplierSettlementUnitPrice()).isEqualTo(9_999L);
    }

    @Test
    void skipsIneligibleCandidatesButKeepsThemInOrderedAuditTrail() {
        RoutingCandidate rejected = ineligible(50, "REJECTED", "HEALTH_ERROR");
        RoutingCandidate winner = eligible(100, "QINAMENG", 10L, 900L);

        RoutingDecision decision = engine.decide(INPUT, List.of(rejected, winner));
        assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("QINAMENG");
        assertThat(decision.orderedCandidates())
                .extracting(RoutingCandidate::supplierCode, RoutingCandidate::eligible)
                .containsExactly(
                        tuple("REJECTED", false),
                        tuple("QINAMENG", true));
    }

    @Test
    void returnsNoEligibleCandidateWhenAllFilteredOut() {
        RoutingDecision decision = engine.decide(INPUT, List.of(
                ineligible(100, "A", "INVENTORY_UNAVAILABLE"),
                ineligible(200, "B", "BALANCE_UNAVAILABLE")));

        assertThat(decision.selected()).isFalse();
        assertThat(decision.selectedCandidate()).isNull();
        assertThat(decision.reasonCode()).isEqualTo("NO_ELIGIBLE_CANDIDATE");
        assertThat(decision.orderedCandidates()).hasSize(2);
    }

    @Test
    void isDeterministicAcrossRepeatedCalls() {
        List<RoutingCandidate> candidates = List.of(
                eligible(100, "QINAMENG", 10L, 900L),
                eligible(200, "ZETA", 20L, 100L));

        RoutingDecision first = engine.decide(INPUT, candidates);
        RoutingDecision second = engine.decide(INPUT, candidates);

        assertThat(second.selectedCandidate()).isEqualTo(first.selectedCandidate());
        assertThat(second.orderedCandidates()).isEqualTo(first.orderedCandidates());
        assertThat(second.reasonCode()).isEqualTo(first.reasonCode());
    }

    private static RoutingCandidate eligible(int priority, String supplierCode, Long offerId, long price) {
        return new RoutingCandidate(
                priority, 1L, supplierCode, supplierCode + " Supplier",
                offerId, 100L, 200L, 300L, price, "CNY",
                true, "AVAILABLE", List.of(),
                null, null, null, null);
    }

    private static RoutingCandidate ineligible(int priority, String supplierCode, String reason) {
        return new RoutingCandidate(
                priority, 1L, supplierCode, supplierCode + " Supplier",
                null, null, null, null, null, "CNY",
                false, "UNAVAILABLE", List.of(reason),
                null, null, null, null);
    }
}
