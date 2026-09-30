package com.aifuli.module.supplychain.border.instore.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 到店购外部操作状态，对应 {@code sc_instore_operation.status}。
 *
 * <p>取值与 DDL CHECK 约束一致：0待执行，10执行中，20结果未知，30成功，40确定失败，90人工处理。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#4-中台侧场景详情模块}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InstoreOperationStatus {
    /** 待执行：PREORDER 建单后初始状态。 */
    PENDING((short) 0, "待执行"),
    /** 执行中：CAS 抢占成功。 */
    RUNNING((short) 10, "执行中"),
    /** 结果未知：网络超时，保持原 outTradeNo/invocationId，由 Timer 查单。 */
    UNKNOWN((short) 20, "结果未知"),
    /** 成功。 */
    SUCCESS((short) 30, "成功"),
    /** 确定失败。 */
    FAILED((short) 40, "确定失败"),
    /** 人工处理。 */
    MANUAL((short) 90, "人工处理");

    private final short code;
    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;

    public static InstoreOperationStatus fromCode(short code) {
        for (InstoreOperationStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown instore operation status: " + code);
    }
}
