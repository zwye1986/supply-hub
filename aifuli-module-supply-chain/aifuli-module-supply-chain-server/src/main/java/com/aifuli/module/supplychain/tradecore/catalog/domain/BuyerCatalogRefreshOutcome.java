package com.aifuli.module.supplychain.tradecore.catalog.domain;

/**
 * 自动目录单次刷新的封闭结果：UPDATED真实更新、UNCHANGED无变化、DEFERRED条件未满足待恢复、
 * FAILED由管理编排捕获技术失败时生成、SKIPPED_MANUAL手工目录不刷新、PAUSED暂停中仅安全评估、
 * TERMINAL退役终态不再评估。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
 */
public enum BuyerCatalogRefreshOutcome {
    UPDATED,
    UNCHANGED,
    DEFERRED,
    FAILED,
    SKIPPED_MANUAL,
    PAUSED,
    TERMINAL
}
