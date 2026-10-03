package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/**
 * 首冻尚未配置当前价格，允许PROCESSING同参续跑；悬空版本、JSON或数据库异常禁止使用此类型。
 * 设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#3-定价身份与当前准入。
 */
public final class ProjectPricingNotReadyException extends IllegalStateException {
    public ProjectPricingNotReadyException(String message) { super(message); }
}
