package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 敏感数据访问审计结果状态，对应 {@code sc_sensitive_access_audit.result_status}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum SensitiveAccessAuditResultStatus {

    SUCCESS((short) 10, "访问成功"),
    REJECTED((short) 20, "访问拒绝"),
    FAILED((short) 30, "访问失败");

    private final short code;
    private final String chineseName;
}
