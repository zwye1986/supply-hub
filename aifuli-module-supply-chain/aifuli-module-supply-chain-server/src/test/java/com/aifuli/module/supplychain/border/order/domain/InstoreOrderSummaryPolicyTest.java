package com.aifuli.module.supplychain.border.order.domain;

import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INSTORE 售后投影单测：按在途 Case 与累计退款投影总表售后状态。
 *
 * <p>售后状态码（sc_supply_chain_order.aftersale_status DDL CHECK）：
 * 0 无售后、10 处理中、20 部分退款、30 全部退款、90 人工处理。
 * 详见 {@code docs/superpowers/plans/2026-08-23-instore-refund-serialization.md}（Task 3）
 * 与 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
 */
class InstoreOrderSummaryPolicyTest {

    private final InstoreOrderSummaryPolicy policy = new InstoreOrderSummaryPolicy();

    private OrderSummaryFact fact() {
        return new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_INSTORE,
                SupplyChainOrderTypes.DETAIL_TYPE_INSTORE_CHANNEL,
                InstoreOrderStatus.COMPLETED.getCode(), 1L, 1L, null);
    }

    @Test
    void noRefundKeepsNone() {
        OrderSummaryDecision decision = policy.decide(fact(),
                new InstoreRefundProjection(false, 0L, 1000L));
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 0);
    }

    @Test
    void openRefundCaseProjectsProcessing() {
        OrderSummaryDecision decision = policy.decide(fact(),
                new InstoreRefundProjection(true, 400L, 1000L));
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 10);
    }

    @Test
    void partialRefundProjectsPartiallyRefunded() {
        OrderSummaryDecision decision = policy.decide(fact(),
                new InstoreRefundProjection(false, 400L, 1000L));
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 20);
        assertThat(decision.orderStatus()).isEqualTo((short) 30);
    }

    @Test
    void fullyRefundedProjectsFullyRefunded() {
        OrderSummaryDecision decision = policy.decide(fact(),
                new InstoreRefundProjection(false, 1000L, 1000L));
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 30);
    }

    @Test
    void nullProjectionKeepsLegacyNone() {
        OrderSummaryDecision decision = policy.decide(fact(), null);
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 0);
    }
}
