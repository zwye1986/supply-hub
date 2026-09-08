package com.aifuli.module.supplychain.border.order.domain;

/**
 * 按 {@code orderType} 唯一计算公共订单状态的策略；场景模块只提交封闭 {@link OrderSummaryFact}，
 * 策略唯一决定总表状态，避免场景详情反向耦合到总表。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-04-supply-chain-core-design.md#订单模型} 与
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#订单模型}。</p>
 */
public interface OrderSummaryPolicy {

    String orderType();

    OrderSummaryDecision decide(OrderSummaryFact fact);
}
