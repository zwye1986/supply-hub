package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 离线迁移安全错误类别，不包含目标或底层异常报文；见
 * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
 */
public enum RechargeFrequencyMigrationCodes {
    MIGRATION_CONFLICT,
    DEPLOYMENT_EVIDENCE_MISSING,
    DEPLOYMENT_EVIDENCE_FAILED,
    TARGET_VERSION_INVALID,
    IDENTITY_KEY_INVALID,
    ASSOCIATION_INVALID,
    TARGET_UNRECOVERABLE,
    TARGET_SCHEMA_INVALID,
    PRODUCT_UNRECOVERABLE,
    ACCEPTANCE_TIME_INVALID,
    MULTIPLE_UNVERIFIED_OPERATIONS,
    STATE_CONTRADICTION,
    FIFO_GAP,
    STALE_QUEUE,
    UNRESOLVED_RISK,
    SUCCESS_WINDOW_PENDING,
    STORAGE_FAILURE
}
