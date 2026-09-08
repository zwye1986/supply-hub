package com.aifuli.module.supplychain.border.order.domain;

import org.springframework.stereotype.Component;

/**
 * CARD 类订单的公共状态策略：受理即进入 PROCESSING，等待供应商履约结果；
 * 售后初始为 NONE，业务阶段为 CARD_AWAITING_RESULT。
 *
 * <p>M1 仅覆盖受理态；履约完成/失败的状态推进在 Task 11 接入适配器后扩展。
 * 设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#订单模型}。</p>
 */
@Component
public class CardOrderSummaryPolicy implements OrderSummaryPolicy {

    private static final short ORDER_STATUS_PROCESSING = 10;
    private static final short AFTERSALE_STATUS_NONE = 0;

    @Override
    public String orderType() {
        return SupplyChainOrderTypes.ORDER_TYPE_CARD;
    }

    @Override
    public OrderSummaryDecision decide(OrderSummaryFact fact) {
        if (fact == null || !SupplyChainOrderTypes.ORDER_TYPE_CARD.equals(fact.orderType())) {
            throw new IllegalArgumentException("CardOrderSummaryPolicy requires CARD fact");
        }
        return new OrderSummaryDecision(ORDER_STATUS_PROCESSING, AFTERSALE_STATUS_NONE,
                SupplyChainOrderTypes.STAGE_CARD_AWAITING_RESULT);
    }
}
