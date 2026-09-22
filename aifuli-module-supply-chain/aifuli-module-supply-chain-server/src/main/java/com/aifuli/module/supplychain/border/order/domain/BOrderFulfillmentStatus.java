package com.aifuli.module.supplychain.border.order.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 供应商采购订单履约状态，对应 {@code sc_b_order.fulfillment_status}。
 *
 * <p>{@code WAITING=0} 对应受理落库的待执行；{@code PENDING=10} 仍表示执行中，名称保持不变以免改写履约生命周期。
 * 管理端码表见 {@code docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md#33-响应字段}。
 * 生命周期推进见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum BOrderFulfillmentStatus {

    WAITING((short) 0, "待执行"),
    PENDING((short) 10, "待履约"),
    PROCESSING((short) 20, "履约中"),
    SUCCEEDED((short) 40, "履约成功"),
    FAILED((short) 50, "履约失败"),
    MANUAL((short) 90, "人工处理");

    private final short code;
    private final String chineseName;

    public static BOrderFulfillmentStatus fromCode(Short code) {
        if (code == null) {
            throw new IllegalArgumentException("Unknown BOrder fulfillment status: null");
        }
        return fromCode(code.shortValue());
    }

    public static BOrderFulfillmentStatus fromCode(short code) {
        for (BOrderFulfillmentStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown BOrder fulfillment status: " + code);
    }
}
