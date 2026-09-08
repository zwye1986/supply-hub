package com.aifuli.module.supplychain.border.order.domain;

import com.aifuli.module.supplychain.border.o2o.domain.O2ODetailStatus;
import org.springframework.stereotype.Component;

/**
 * O2O 类订单的公共状态策略：按 O2O 详情聚合状态计算公共主状态与业务阶段。
 *
 * <p>本期 225 暂不接入，售后状态由退款事实单独推进；此处仅映射主状态。
 * 设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
@Component
public class O2OOrderSummaryPolicy implements OrderSummaryPolicy {

    private static final short ORDER_STATUS_CREATED = 0;
    private static final short ORDER_STATUS_PROCESSING = 10;
    private static final short ORDER_STATUS_SUCCEEDED = 30;
    private static final short ORDER_STATUS_FAILED = 40;
    private static final short ORDER_STATUS_CANCELLED = 50;
    private static final short ORDER_STATUS_MANUAL = 90;
    private static final short AFTERSALE_STATUS_NONE = 0;

    @Override
    public String orderType() {
        return SupplyChainOrderTypes.ORDER_TYPE_O2O;
    }

    @Override
    public OrderSummaryDecision decide(OrderSummaryFact fact) {
        if (fact == null || !SupplyChainOrderTypes.ORDER_TYPE_O2O.equals(fact.orderType())) {
            throw new IllegalArgumentException("O2OOrderSummaryPolicy requires O2O fact");
        }
        O2ODetailStatus detail = O2ODetailStatus.fromCode(fact.fulfillmentStatus());
        return switch (detail) {
            case CREATED -> new OrderSummaryDecision(ORDER_STATUS_PROCESSING, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_O2O_AWAITING_PAYMENT);
            case PROCESSING -> new OrderSummaryDecision(ORDER_STATUS_PROCESSING, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_O2O_AWAITING_PAYMENT);
            case COMPLETED -> new OrderSummaryDecision(ORDER_STATUS_SUCCEEDED, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_O2O_PAID);
            case CANCELLED -> new OrderSummaryDecision(ORDER_STATUS_CANCELLED, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_O2O_CANCELLED);
            case FAILED -> new OrderSummaryDecision(ORDER_STATUS_FAILED, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_O2O_FAILED);
            case MANUAL -> new OrderSummaryDecision(ORDER_STATUS_MANUAL, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_O2O_FAILED);
        };
    }
}
