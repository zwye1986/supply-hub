package com.aifuli.module.supplychain.border.order.domain;

import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import org.springframework.stereotype.Component;

/**
 * INSTORE 类订单的公共状态策略：按到店购渠道订单头聚合状态计算公共主状态与业务阶段。
 *
 * <p>到店即完成，支付成功即 SUCCEEDED；无 O2O 的 225 推送与渠道通知收敛。
 * 售后状态由退款 Case 金额投影：建单初始（无退款投影）售后恒 NONE，
 * 退款 Case 状态变更后由 Lifecycle 组装 {@link InstoreRefundProjection} 走
 * {@link #decide(OrderSummaryFact, InstoreRefundProjection)} 投影。
 * 按 orderType 唯一注册，与 {@link CardOrderSummaryPolicy}/{@link O2OOrderSummaryPolicy} 平行，互不冲突。
 * 设计详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
 */
@Component
public class InstoreOrderSummaryPolicy implements OrderSummaryPolicy {

    private static final short ORDER_STATUS_PROCESSING = 10;
    private static final short ORDER_STATUS_SUCCEEDED = 30;
    private static final short ORDER_STATUS_FAILED = 40;
    private static final short ORDER_STATUS_CANCELLED = 50;
    private static final short ORDER_STATUS_MANUAL = 90;
    private static final short AFTERSALE_STATUS_NONE = 0;
    private static final short AFTERSALE_STATUS_PROCESSING = 10;
    private static final short AFTERSALE_STATUS_PARTIALLY_REFUNDED = 20;
    private static final short AFTERSALE_STATUS_FULLY_REFUNDED = 30;

    @Override
    public String orderType() {
        return SupplyChainOrderTypes.ORDER_TYPE_INSTORE;
    }

    @Override
    public OrderSummaryDecision decide(OrderSummaryFact fact) {
        if (fact == null || !SupplyChainOrderTypes.ORDER_TYPE_INSTORE.equals(fact.orderType())) {
            throw new IllegalArgumentException("InstoreOrderSummaryPolicy requires INSTORE fact");
        }
        InstoreOrderStatus detail = InstoreOrderStatus.fromCode(fact.fulfillmentStatus());
        return switch (detail) {
            case CREATED, PROCESSING -> new OrderSummaryDecision(ORDER_STATUS_PROCESSING, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_INSTORE_AWAITING_PAYMENT);
            case COMPLETED -> new OrderSummaryDecision(ORDER_STATUS_SUCCEEDED, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_INSTORE_PAID);
            case CANCELLED -> new OrderSummaryDecision(ORDER_STATUS_CANCELLED, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_INSTORE_CANCELLED);
            case FAILED -> new OrderSummaryDecision(ORDER_STATUS_FAILED, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_INSTORE_FAILED);
            case MANUAL -> new OrderSummaryDecision(ORDER_STATUS_MANUAL, AFTERSALE_STATUS_NONE,
                    SupplyChainOrderTypes.STAGE_INSTORE_FAILED);
        };
    }

    /**
     * 带退款投影的裁决：主状态与业务阶段沿用 {@link #decide(OrderSummaryFact)}，
     * 售后按"在途优先，其次按累计/实付"投影。无在途且累计为 0 → NONE；
     * 有在途或人工 Case → PROCESSING；累计达实付 → FULLY_REFUNDED；其余正累计 → PARTIALLY_REFUNDED。
     *
     * <p>投影 null 表示联调切片建单初始投影（无退款信息），沿用基础裁决售后恒 NONE。
     * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}（售后目标态）。</p>
     */
    public OrderSummaryDecision decide(OrderSummaryFact fact, InstoreRefundProjection refundProjection) {
        OrderSummaryDecision base = decide(fact);
        if (refundProjection == null) {
            return base;
        }
        short aftersale;
        if (refundProjection.hasOpenRefund()) {
            aftersale = AFTERSALE_STATUS_PROCESSING;
        } else if (refundProjection.totalChannelRefundAmount() > 0
                && refundProjection.totalChannelRefundAmount() >= refundProjection.totalPaidAmount()) {
            aftersale = AFTERSALE_STATUS_FULLY_REFUNDED;
        } else if (refundProjection.totalChannelRefundAmount() > 0) {
            aftersale = AFTERSALE_STATUS_PARTIALLY_REFUNDED;
        } else {
            aftersale = AFTERSALE_STATUS_NONE;
        }
        return new OrderSummaryDecision(base.orderStatus(), aftersale, base.businessStage());
    }
}
