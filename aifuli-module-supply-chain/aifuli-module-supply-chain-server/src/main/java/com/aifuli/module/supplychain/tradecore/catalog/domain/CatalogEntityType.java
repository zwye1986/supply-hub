package com.aifuli.module.supplychain.tradecore.catalog.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 商品中心可批量操作的实体类型。
 * <p>批量上下架回执中用于标识失败项归属，取值直接作为对外契约的 {@code entityType} 字段，
 * 因此以显式 {@code code} 承载而非依赖 {@link #name()}，避免重命名常量时静默破坏前端契约。
 * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status}。
 */
@Getter
@RequiredArgsConstructor
public enum CatalogEntityType {
    SPU("SPU"),
    SKU("SKU");

    private final String code;
}
