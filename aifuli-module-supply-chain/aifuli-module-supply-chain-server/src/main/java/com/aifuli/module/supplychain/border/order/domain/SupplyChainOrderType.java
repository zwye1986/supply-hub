package com.aifuli.module.supplychain.border.order.domain;

/**
 * 供应链总订单类型，对应 {@code sc_supply_chain_order.order_type} DDL CHECK。
 * 设计见 {@code docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md#31-分页列表}。
 */
public enum SupplyChainOrderType {
    CARD,
    O2O,
    INSTORE
}
