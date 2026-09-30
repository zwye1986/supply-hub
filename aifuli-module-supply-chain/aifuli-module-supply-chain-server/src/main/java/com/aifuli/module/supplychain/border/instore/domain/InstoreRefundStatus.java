package com.aifuli.module.supplychain.border.instore.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 到店购退款 Case 状态，对应 {@code sc_instore_refund_case.status}。
 *
 * <p>取值与 DDL CHECK 约束一致：0待处理，10排队中，20采购商退款中，30退款成功，40退款失败，60已完成，90人工处理。
 * 与 O2O 退款状态区别：无 50通知渠道中（旅通为 pull 模型，中台不推送渠道，旅通主动来查退款状态）。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#4-中台侧场景详情模块}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InstoreRefundStatus {
    /** 待处理。 */
    PENDING((short) 0, "待处理"),
    /** 排队中：同一支付流水最多一个活动退款，其余按顺序排队。 */
    QUEUED((short) 10, "排队中"),
    /** 采购商退款中。 */
    BUYER_REFUNDING((short) 20, "采购商退款中"),
    /** 退款成功。 */
    SUCCESS((short) 30, "退款成功"),
    /** 退款失败。 */
    FAILED((short) 40, "退款失败"),
    /** 已完成。 */
    COMPLETED((short) 60, "已完成"),
    /** 人工处理。 */
    MANUAL((short) 90, "人工处理");

    private final short code;
    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;

    public static InstoreRefundStatus fromCode(short code) {
        for (InstoreRefundStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown instore refund status: " + code);
    }
}
