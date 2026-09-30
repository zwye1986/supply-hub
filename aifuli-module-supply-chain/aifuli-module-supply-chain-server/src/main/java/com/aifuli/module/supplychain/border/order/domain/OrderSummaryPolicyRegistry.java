package com.aifuli.module.supplychain.border.order.domain;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按 {@code orderType} 唯一解析 OrderSummaryPolicy；启动时发现重复注册即失败关闭。 */
@Component
public class OrderSummaryPolicyRegistry {

    private final Map<String, OrderSummaryPolicy> policies;

    public OrderSummaryPolicyRegistry(List<OrderSummaryPolicy> policies) {
        Map<String, OrderSummaryPolicy> map = new HashMap<>();
        for (OrderSummaryPolicy policy : policies) {
            OrderSummaryPolicy existing = map.put(policy.orderType(), policy);
            if (existing != null) {
                throw new IllegalStateException("Duplicate OrderSummaryPolicy for orderType: "
                        + policy.orderType());
            }
        }
        this.policies = map;
    }

    public OrderSummaryPolicy require(String orderType) {
        OrderSummaryPolicy policy = policies.get(orderType);
        if (policy == null) {
            throw new IllegalStateException("No OrderSummaryPolicy registered for orderType: " + orderType);
        }
        return policy;
    }
}
