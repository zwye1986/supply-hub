package com.aifuli.module.supplychain.border.order.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 供应商采购订单履约状态，对应 {@code sc_b_order.fulfillment_status}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum BOrderFulfillmentStatus {

    PENDING((short) 10, "待履约"),
    PROCESSING((short) 20, "履约中"),
    SUCCEEDED((short) 40, "履约成功"),
    FAILED((short) 50, "履约失败"),
    MANUAL((short) 90, "人工处理");

    private final short code;
    private final String chineseName;

    public static BOrderFulfillmentStatus fromCode(short code) {
        for (BOrderFulfillmentStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown BOrder fulfillment status: " + code);
    }
}
