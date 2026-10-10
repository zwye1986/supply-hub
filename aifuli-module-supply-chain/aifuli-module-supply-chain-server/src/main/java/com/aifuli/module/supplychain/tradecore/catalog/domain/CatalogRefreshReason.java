package com.aifuli.module.supplychain.tradecore.catalog.domain;

/** 自动目录刷新稳定原因，见最小业务设计 §4；真实路由原因原样保留。 */
public enum CatalogRefreshReason {
    MANUAL, SUSPENDED, RETIRED, DEFERRED_PRICE, BUYER_PRODUCT_REMOVED, CONFIRMED,
    NOT_EVALUATED, PROJECTION_EXPIRED, SOURCE_CHANGED
}
