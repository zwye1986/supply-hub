package com.aifuli.module.supplychain.border.order.domain;

import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2ODetailStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 总订单公共状态策略单测：CARD/O2O/INSTORE 状态机与按 orderType 唯一注册。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁}
 * 与 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#43-InstoreOrderSummaryPolicy}。</p>
 */
class OrderSummaryPolicyTest {

    private final CardOrderSummaryPolicy cardPolicy = new CardOrderSummaryPolicy();
    private final O2OOrderSummaryPolicy o2oPolicy = new O2OOrderSummaryPolicy();
    private final InstoreOrderSummaryPolicy instorePolicy = new InstoreOrderSummaryPolicy();

    @Test
    void cardAcceptanceAlwaysMapsToProcessingAwaitingResult() {
        OrderSummaryFact fact = new OrderSummaryFact(
                SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                (short) 10, 1L, 0L, null);

        OrderSummaryDecision decision = cardPolicy.decide(fact);

        assertThat(cardPolicy.orderType()).isEqualTo(SupplyChainOrderTypes.ORDER_TYPE_CARD);
        assertThat(decision.orderStatus()).isEqualTo((short) 10);
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 0);
        assertThat(decision.businessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_CARD_AWAITING_RESULT);
    }

    @Test
    void cardPolicyRejectsNullOrWrongOrderType() {
        assertThatThrownBy(() -> cardPolicy.decide(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CARD");
        OrderSummaryFact o2oFact = new OrderSummaryFact(
                SupplyChainOrderTypes.ORDER_TYPE_O2O,
                SupplyChainOrderTypes.DETAIL_TYPE_O2O_CHANNEL,
                O2ODetailStatus.CREATED.getCode(), 1L, 0L, null);
        assertThatThrownBy(() -> cardPolicy.decide(o2oFact))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(O2ODetailStatus.class)
    void o2oPolicyMapsEveryDetailStatus(O2ODetailStatus detailStatus) {
        OrderSummaryFact fact = new OrderSummaryFact(
                SupplyChainOrderTypes.ORDER_TYPE_O2O,
                SupplyChainOrderTypes.DETAIL_TYPE_O2O_CHANNEL,
                detailStatus.getCode(), 1L, 1L, null);

        OrderSummaryDecision decision = o2oPolicy.decide(fact);

        assertThat(o2oPolicy.orderType()).isEqualTo(SupplyChainOrderTypes.ORDER_TYPE_O2O);
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 0);
        switch (detailStatus) {
            case CREATED, PROCESSING -> {
                assertThat(decision.orderStatus()).isEqualTo((short) 10);
                assertThat(decision.businessStage())
                        .isEqualTo(SupplyChainOrderTypes.STAGE_O2O_AWAITING_PAYMENT);
            }
            case COMPLETED -> {
                assertThat(decision.orderStatus()).isEqualTo((short) 30);
                assertThat(decision.businessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_O2O_PAID);
            }
            case CANCELLED -> {
                assertThat(decision.orderStatus()).isEqualTo((short) 50);
                assertThat(decision.businessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_O2O_CANCELLED);
            }
            case FAILED -> {
                assertThat(decision.orderStatus()).isEqualTo((short) 40);
                assertThat(decision.businessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_O2O_FAILED);
            }
            case MANUAL -> {
                assertThat(decision.orderStatus()).isEqualTo((short) 90);
                assertThat(decision.businessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_O2O_FAILED);
            }
        }
    }

    @Test
    void o2oPolicyRejectsUnknownDetailStatus() {
        OrderSummaryFact fact = new OrderSummaryFact(
                SupplyChainOrderTypes.ORDER_TYPE_O2O,
                SupplyChainOrderTypes.DETAIL_TYPE_O2O_CHANNEL,
                (short) 99, 1L, 0L, null);
        assertThatThrownBy(() -> o2oPolicy.decide(fact))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown O2O detail status");
    }

    @ParameterizedTest
    @EnumSource(InstoreOrderStatus.class)
    void instorePolicyMapsEveryDetailStatus(InstoreOrderStatus detailStatus) {
        OrderSummaryFact fact = new OrderSummaryFact(
                SupplyChainOrderTypes.ORDER_TYPE_INSTORE,
                SupplyChainOrderTypes.DETAIL_TYPE_INSTORE_CHANNEL,
                detailStatus.getCode(), 1L, 1L, null);

        OrderSummaryDecision decision = instorePolicy.decide(fact);

        assertThat(instorePolicy.orderType()).isEqualTo(SupplyChainOrderTypes.ORDER_TYPE_INSTORE);
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 0);
        switch (detailStatus) {
            case CREATED, PROCESSING -> {
                assertThat(decision.orderStatus()).isEqualTo((short) 10);
                assertThat(decision.businessStage())
                        .isEqualTo(SupplyChainOrderTypes.STAGE_INSTORE_AWAITING_PAYMENT);
            }
            case COMPLETED -> {
                assertThat(decision.orderStatus()).isEqualTo((short) 30);
                assertThat(decision.businessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_INSTORE_PAID);
            }
            case CANCELLED -> {
                assertThat(decision.orderStatus()).isEqualTo((short) 50);
                assertThat(decision.businessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_INSTORE_CANCELLED);
            }
            case FAILED -> {
                assertThat(decision.orderStatus()).isEqualTo((short) 40);
                assertThat(decision.businessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_INSTORE_FAILED);
            }
            case MANUAL -> {
                assertThat(decision.orderStatus()).isEqualTo((short) 90);
                assertThat(decision.businessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_INSTORE_FAILED);
            }
        }
    }

    @Test
    void instorePolicyRejectsNullOrWrongOrderType() {
        assertThatThrownBy(() -> instorePolicy.decide(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INSTORE");
        OrderSummaryFact o2oFact = new OrderSummaryFact(
                SupplyChainOrderTypes.ORDER_TYPE_O2O,
                SupplyChainOrderTypes.DETAIL_TYPE_O2O_CHANNEL,
                O2ODetailStatus.CREATED.getCode(), 1L, 0L, null);
        assertThatThrownBy(() -> instorePolicy.decide(o2oFact))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryResolvesByOrderTypeAndRejectsDuplicates() {
        OrderSummaryPolicyRegistry registry = new OrderSummaryPolicyRegistry(
                List.of(cardPolicy, o2oPolicy, instorePolicy));
        assertThat(registry.require(SupplyChainOrderTypes.ORDER_TYPE_CARD)).isSameAs(cardPolicy);
        assertThat(registry.require(SupplyChainOrderTypes.ORDER_TYPE_O2O)).isSameAs(o2oPolicy);
        assertThat(registry.require(SupplyChainOrderTypes.ORDER_TYPE_INSTORE)).isSameAs(instorePolicy);
        assertThatThrownBy(() -> registry.require("UNKNOWN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNKNOWN");
        assertThatThrownBy(() -> new OrderSummaryPolicyRegistry(List.of(cardPolicy, new CardOrderSummaryPolicy())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }
}
