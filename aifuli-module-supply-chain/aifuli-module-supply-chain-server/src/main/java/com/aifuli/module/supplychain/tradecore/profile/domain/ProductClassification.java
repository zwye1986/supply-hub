package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * 商品分类封闭枚举：存量供货命中码仍可用枚举名；d34 起写入亦可为类目 {@code category_code}。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#23-商品分类-productclassification}、
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d34）。
 */
public enum ProductClassification {
    ELECTRONICS,
    VIRTUAL_CARD,
    MOVIE_TICKET
}
