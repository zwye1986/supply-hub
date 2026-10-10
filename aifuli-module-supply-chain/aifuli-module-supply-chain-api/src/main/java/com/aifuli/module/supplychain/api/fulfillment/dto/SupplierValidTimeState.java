package com.aifuli.module.supplychain.api.fulfillment.dto;

/**
 * 供应商有效期字段状态：缺失、显式null、字符串值（包括空字符串）。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#72-task7授权查询与有效期展示契约。
 */
public enum SupplierValidTimeState {
    ABSENT, NULL, VALUE
}
