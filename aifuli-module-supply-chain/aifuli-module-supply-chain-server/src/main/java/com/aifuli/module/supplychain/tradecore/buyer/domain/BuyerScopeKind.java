package com.aifuli.module.supplychain.tradecore.buyer.domain;

/**
 * 分销商可售范围的维度：品类或类型；对应 {@code sc_buyer_category_scope.scope_kind}。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#43-分销商范围表}。
 */
public enum BuyerScopeKind {
    SELLABLE,
    PRODUCT_TYPE
}
