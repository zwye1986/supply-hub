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
 * 纯 {@link SupplierRoutingEngine} 单测：三优先级策略排序（STABLE/PRICE/COMPOSITE）、
 * 归一化边界（价格全同）、资格过滤与同分稳定裁决。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#54-候选解析与三策略排序}。</p>
 */
class SupplierRoutingEngineTest {

    private static final RoutingInput INPUT = new RoutingInput(
            1L, null, 2L, "DIRECT_RECHARGE", 1L,
            LocalDateTime.of(2026, 9, 23, 10, 0), Map.of());

    /** 默认排序：稳定→价格→综合，权重 40/30/30。 */
    private static final RoutingSortConfig STABLE_PRICE_COMPOSITE =
            new RoutingSortConfig(RoutingStrategy.STABLE, RoutingStrategy.PRICE, RoutingStrategy.COMPOSITE,
                    40, 30, 30);

    private final SupplierRoutingEngine engine = new SupplierRoutingEngine();

    @Test
    void rejectsNullInputOrCandidatesOrSortConfig() {
        List<RoutingCandidate> candidates = List.of(eligible(100, "QNM", 10L, 900L, 80, 70));
        assertThatThrownBy(() -> engine.decide(null, candidates, STABLE_PRICE_COMPOSITE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.decide(INPUT, null, STABLE_PRICE_COMPOSITE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.decide(INPUT, candidates, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void stableFirstOrdersByStabilityScoreDescending() {
        // 首位 STABLE：稳定性评分高者优先，价格不参与首位比较
        RoutingCandidate stableCheap = eligible(100, "ALPHA", 10L, 900L, 90, 10);
        RoutingCandidate unstableExpensive = eligible(200, "ZETA", 20L, 100L, 10, 90);

        RoutingDecision decision = engine.decide(INPUT, List.of(unstableExpensive, stableCheap),
                STABLE_PRICE_COMPOSITE);
        assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("ALPHA");
        assertThat(decision.reasonCode()).isEqualTo("SELECTED_BY_STRATEGY_STABLE_PRICE_COMPOSITE");
        assertThat(decision.orderedCandidates())
                .extracting(RoutingCandidate::supplierCode)
                .containsExactly("ALPHA", "ZETA");
    }

    @Test
    void priceFirstOrdersByPriceAscending() {
        RoutingSortConfig priceFirst = new RoutingSortConfig(RoutingStrategy.PRICE,
                RoutingStrategy.STABLE, RoutingStrategy.COMPOSITE, 40, 30, 30);
        RoutingCandidate cheap = eligible(100, "CHEAP", 10L, 100L, 10, 10);
        RoutingCandidate expensive = eligible(200, "EXPENSIVE", 20L, 9_999L, 99, 99);

        RoutingDecision decision = engine.decide(INPUT, List.of(expensive, cheap), priceFirst);
        assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("CHEAP");
        assertThat(decision.orderedCandidates())
                .extracting(RoutingCandidate::supplierCode)
                .containsExactly("CHEAP", "EXPENSIVE");
    }

    @Test
    void compositeFirstOrdersByWeightedScoreDescending() {
        // 权重 0/100/0 的综合分即归一化价格分：低价者胜
        RoutingSortConfig compositeFirst = new RoutingSortConfig(RoutingStrategy.COMPOSITE,
                RoutingStrategy.PRICE, RoutingStrategy.STABLE, 0, 100, 0);
        RoutingCandidate min = eligible(100, "MIN", 10L, 100L, 10, 10);
        RoutingCandidate max = eligible(200, "MAX", 20L, 1_100L, 99, 99);

        RoutingDecision decision = engine.decide(INPUT, List.of(max, min), compositeFirst);
        assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("MIN");
        // 归一化价格分：MIN=100，MAX=0
        assertThat(decision.orderedCandidates())
                .extracting(RoutingCandidate::supplierCode)
                .containsExactly("MIN", "MAX");
    }

    @Test
    void compositeTieBreaksBySecondStrategyThenStableRules() {
        // 两位候选综合分相同（评分对称），第二位 PRICE 低价者胜
        RoutingSortConfig compositePriceStable = new RoutingSortConfig(RoutingStrategy.COMPOSITE,
                RoutingStrategy.PRICE, RoutingStrategy.STABLE, 50, 0, 50);
        RoutingCandidate lowPrice = eligible(100, "LOW", 10L, 100L, 50, 50);
        RoutingCandidate highPrice = eligible(200, "HIGH", 20L, 500L, 60, 40);

        RoutingDecision decision = engine.decide(INPUT, List.of(highPrice, lowPrice), compositePriceStable);
        assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("LOW");
    }

    @Test
    void equalPricesGiveFullPriceScoreToAllCandidates() {
        // 候选集价格全部相同（span=0）：价格分记满分，综合分退化为评分加权和
        RoutingSortConfig compositeFirst = new RoutingSortConfig(RoutingStrategy.COMPOSITE,
                RoutingStrategy.STABLE, RoutingStrategy.PRICE, 40, 30, 30);
        RoutingCandidate stable = eligible(100, "STABLE", 10L, 500L, 90, 10);
        RoutingCandidate unstable = eligible(200, "UNSTABLE", 20L, 500L, 10, 90);

        RoutingDecision decision = engine.decide(INPUT, List.of(unstable, stable), compositeFirst);
        // 价格分均为100：稳定 40*90+30*100+30*10 > 不稳定 40*10+30*100+30*90
        assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("STABLE");
    }

    @Test
    void breaksTiesByCandidatePriorityThenSupplierCodeThenOfferId() {
        // 全部策略位同分：candidatePriority（小者先）→ supplierCode → offerId 稳定裁决；
        // AAA 优先级 200 最大，尽管编码字典序最小也只能排最后。
        RoutingCandidate beta = eligible(100, "BETA", 20L, 500L, 50, 50);
        RoutingCandidate alpha = eligible(100, "ALPHA", 30L, 500L, 50, 50);
        RoutingCandidate alphaEarlierOffer = eligible(100, "ALPHA", 10L, 500L, 50, 50);
        RoutingCandidate lowestPriority = eligible(200, "AAA", 40L, 500L, 50, 50);

        RoutingDecision decision = engine.decide(INPUT,
                List.of(beta, alpha, lowestPriority, alphaEarlierOffer), STABLE_PRICE_COMPOSITE);
        assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("ALPHA");
        assertThat(decision.selectedCandidate().offerId()).isEqualTo(10L);
        assertThat(decision.orderedCandidates())
                .extracting(RoutingCandidate::supplierCode, RoutingCandidate::offerId)
                .containsExactly(
                        tuple("ALPHA", 10L),
                        tuple("ALPHA", 30L),
                        tuple("BETA", 20L),
                        tuple("AAA", 40L));
    }

    @Test
    void skipsIneligibleCandidatesButKeepsThemInOrderedAuditTrail() {
        // 不可用候选评分/价格缺失按 nullsLast 参与排序，保留在审计轨迹但不参与选择
        RoutingCandidate rejected = ineligible(50, "REJECTED", "HEALTH_ERROR");
        RoutingCandidate winner = eligible(100, "QINAMENG", 10L, 900L, 80, 80);

        RoutingDecision decision = engine.decide(INPUT, List.of(rejected, winner), STABLE_PRICE_COMPOSITE);
        assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("QINAMENG");
        assertThat(decision.orderedCandidates())
                .extracting(RoutingCandidate::supplierCode, RoutingCandidate::eligible)
                .containsExactly(
                        tuple("QINAMENG", true),
                        tuple("REJECTED", false));
    }

    @Test
    void returnsNoEligibleCandidateWhenAllFilteredOut() {
        RoutingDecision decision = engine.decide(INPUT, List.of(
                ineligible(100, "A", "INVENTORY_UNAVAILABLE"),
                ineligible(200, "B", "BALANCE_UNAVAILABLE")), STABLE_PRICE_COMPOSITE);

        assertThat(decision.selected()).isFalse();
        assertThat(decision.selectedCandidate()).isNull();
        assertThat(decision.reasonCode()).isEqualTo("NO_ELIGIBLE_CANDIDATE");
        assertThat(decision.orderedCandidates()).hasSize(2);
    }

    @Test
    void isDeterministicAcrossShufflesAndRepeatedCalls() {
        RoutingCandidate primary = eligible(100, "QINAMENG", 10L, 900L, 90, 90);
        RoutingCandidate fallback = eligible(200, "ZETA", 20L, 100L, 50, 50);
        List<RoutingCandidate> shuffled = new ArrayList<>(List.of(fallback, primary));
        Random random = new Random(20260923L);
        for (int attempt = 0; attempt < 100; attempt++) {
            Collections.shuffle(shuffled, random);
            RoutingDecision decision = engine.decide(INPUT, shuffled, STABLE_PRICE_COMPOSITE);
            assertThat(decision.selectedCandidate().supplierCode()).isEqualTo("QINAMENG");
            assertThat(decision.orderedCandidates())
                    .extracting(RoutingCandidate::supplierCode)
                    .containsExactly("QINAMENG", "ZETA");
        }
    }

    private static RoutingCandidate eligible(int priority, String supplierCode, Long offerId, long price,
                                             int stabilityScore, int timelinessScore) {
        return new RoutingCandidate(
                priority, 1L, supplierCode, supplierCode + " Supplier",
                offerId, 100L, 200L, 300L, price, "CNY",
                stabilityScore, timelinessScore,
                true, "AVAILABLE", List.of(),
                null, null, null, null);
    }

    private static RoutingCandidate ineligible(int priority, String supplierCode, String reason) {
        return new RoutingCandidate(
                priority, 1L, supplierCode, supplierCode + " Supplier",
                null, null, null, null, null, "CNY",
                null, null,
                false, "UNAVAILABLE", List.of(reason),
                null, null, null, null);
    }
}
