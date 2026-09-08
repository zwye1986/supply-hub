package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * 商品分类封闭枚举：仅供货配置行与平台SPU使用，分销商档案无此级。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#23-商品分类-productclassification}。
 */
public enum ProductClassification {
    ELECTRONICS,
    VIRTUAL_CARD,
    MOVIE_TICKET
}
