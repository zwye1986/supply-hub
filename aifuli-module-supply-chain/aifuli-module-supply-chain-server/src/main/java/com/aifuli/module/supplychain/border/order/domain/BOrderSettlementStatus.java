package com.aifuli.module.supplychain.border.order.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * B 端履约尝试结算匹配状态，对应 {@code sc_b_order.settlement_status}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum BOrderSettlementStatus {

    MATCHED((short) 10, "已匹配");

    private final short code;
    private final String chineseName;
}
