package com.aifuli.module.supplychain.routing.domain;

/**
 * 路由优先级策略。PRD 模型下引擎按规则配置的三个优先级位次逐级比较候选。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#54-候选解析与三策略排序}。</p>
 */
public enum RoutingStrategy {

    /** 稳定优先：offer 人工录入稳定性评分（0-100）降序。 */
    STABLE("稳定优先"),
    /** 价格优先：offer 冻结供货价（分）升序。 */
    PRICE("价格优先"),
    /** 综合排序：全局权重合成稳定性分+归一化价格分+时效分，降序。 */
    COMPOSITE("综合排序");

    private final String displayName;

    RoutingStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
