package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 敏感访问审计类型，对应 {@code sc_sensitive_access_audit.access_type} CHECK。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
public enum SensitiveAccessAuditAccessType {
    TARGET_DECRYPT,
    ASSET_QUERY,
    ASSET_DECRYPT,
    ASSET_EXPORT
}
