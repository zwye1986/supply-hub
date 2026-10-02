package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 迁移占用时间依据；非迁移项为空。见
 * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
 */
public enum RechargeFrequencyMigrationTimeBasis {
    /** 可核实原充值发送时间，禁止使用查询、回调或操作创建时间。 */
    VERIFIED_SEND,
    /** 原发送时间无法证明时使用本次数据库迁移时间。 */
    MIGRATION_CLOCK
}
