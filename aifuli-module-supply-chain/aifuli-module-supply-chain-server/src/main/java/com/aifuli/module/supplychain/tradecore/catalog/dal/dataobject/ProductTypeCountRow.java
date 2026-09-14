package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

/**
 * 商品类型未归档 SPU 与 SKU 双指标聚合计数统计行。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#28-商品管理列表端模型增强与类型优化030-changeset}。
 */
public record ProductTypeCountRow(String productType, long spuCount, long skuCount) {
}
