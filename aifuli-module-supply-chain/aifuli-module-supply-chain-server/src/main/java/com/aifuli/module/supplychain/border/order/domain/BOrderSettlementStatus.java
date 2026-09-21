package com.aifuli.module.supplychain.border.order.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * B 端履约尝试结算匹配状态，对应 {@code sc_b_order.settlement_status}。
 *
 * <p>码表补齐 DDL 的 0/20/90；管理端见 {@code docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md#33-响应字段}。
 * 生命周期见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum BOrderSettlementStatus {

    PENDING((short) 0, "待报告"),
    MATCHED((short) 10, "已匹配"),
    MISMATCH((short) 20, "有差异"),
    MANUAL((short) 90, "人工处理");

    private final short code;
    private final String chineseName;

    public static BOrderSettlementStatus fromCode(Short code) {
        if (code == null) {
            throw new IllegalArgumentException("Unknown BOrder settlement status: null");
        }
        for (BOrderSettlementStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown BOrder settlement status: " + code);
    }
}
