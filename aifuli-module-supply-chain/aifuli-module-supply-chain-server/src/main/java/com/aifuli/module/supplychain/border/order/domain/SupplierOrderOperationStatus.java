package com.aifuli.module.supplychain.border.order.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 供应商订单操作状态，对应 {@code sc_fulfillment_operation.status}。
 *
 * <p>详见详细设计 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum SupplierOrderOperationStatus {

    /** 待执行（0）。 */
    PENDING((short) 0, "待执行"),
    /** 已认领（10）。 */
    CLAIMED((short) 10, "已认领"),
    /** 执行中（20）。 */
    EXECUTING((short) 20, "执行中"),
    /** 结果未知（30）。 */
    RESULT_UNKNOWN((short) 30, "结果未知"),
    /** 操作成功（40）。 */
    SUCCEEDED((short) 40, "操作成功"),
    /** 操作明确失败（50）。 */
    FAILED((short) 50, "操作明确失败"),
    /** 停止自动执行/人工介入（90）。 */
    MANUAL((short) 90, "停止自动执行");

    private final short code;
    private final String chineseName;

    public static SupplierOrderOperationStatus fromCode(short code) {
        for (SupplierOrderOperationStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown supplier order operation status: " + code);
    }
}
