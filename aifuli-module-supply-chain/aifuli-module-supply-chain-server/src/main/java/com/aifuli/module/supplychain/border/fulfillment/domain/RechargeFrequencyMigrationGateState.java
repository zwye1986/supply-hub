package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 存量候选覆盖发布门禁；见
 * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
 */
public enum RechargeFrequencyMigrationGateState {
    CLOSED,
    READY
}
