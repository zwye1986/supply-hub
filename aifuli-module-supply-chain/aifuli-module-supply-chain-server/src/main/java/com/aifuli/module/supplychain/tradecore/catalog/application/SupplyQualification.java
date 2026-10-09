package com.aifuli.module.supplychain.tradecore.catalog.application;

/**
 * 本次真实路由首选的供给资格，不能与买家级目录 AVAILABLE 投影互换。
 * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#71-资格语义不变。
 */
public enum SupplyQualification {
    CONFIRMED,
    UNKNOWN,
    UNAVAILABLE
}
