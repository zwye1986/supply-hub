package com.aifuli.module.supplychain.border.o2o.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * O2O 退款 Case 状态，对应 {@code sc_o2o_refund_case.status}。
 *
 * <p>取值与 DDL CHECK 约束一致：0待处理，10排队中，20采购商退款中，30退款成功，
 * 40退款失败，50通知渠道中，60已完成，90人工处理。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.3-O2ORefundCase}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum O2ORefundStatus {
    /** 待处理。 */
    PENDING((short) 0, "待处理"),
    /** 排队中：同一详情最多一个 BUYER_REFUNDING，其余按顺序排队。 */
    QUEUED((short) 10, "排队中"),
    /** 采购商退款中。 */
    BUYER_REFUNDING((short) 20, "采购商退款中"),
    /** 退款成功。 */
    SUCCESS((short) 30, "退款成功"),
    /** 退款失败。 */
    FAILED((short) 40, "退款失败"),
    /** 通知渠道中。 */
    NOTIFYING((short) 50, "通知渠道中"),
    /** 已完成。 */
    COMPLETED((short) 60, "已完成"),
    /** 人工处理。 */
    MANUAL((short) 90, "人工处理");

    private final short code;
    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;

    public static O2ORefundStatus fromCode(short code) {
        for (O2ORefundStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown O2O refund status: " + code);
    }
}
