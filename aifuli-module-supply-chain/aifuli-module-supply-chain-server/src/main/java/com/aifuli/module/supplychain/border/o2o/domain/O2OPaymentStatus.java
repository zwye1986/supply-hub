package com.aifuli.module.supplychain.border.o2o.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * O2O 用户支付状态，对应 {@code sc_o2o_channel_detail.payment_status}。
 *
 * <p>取值与 DDL CHECK 约束一致：0待支付，10支付处理中，20已支付，30支付失败，40已关闭，90人工处理。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.2-O2OChannelDetail}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum O2OPaymentStatus {
    /** 待支付：建单后、抢占收银台前。 */
    PENDING((short) 0, "待支付"),
    /** 支付处理中：收银台已拉起。 */
    PROCESSING((short) 10, "支付处理中"),
    /** 已支付：收银台回调确认成功。 */
    PAID((short) 20, "已支付"),
    /** 支付失败。 */
    FAILED((short) 30, "支付失败"),
    /** 已关闭。 */
    CLOSED((short) 40, "已关闭"),
    /** 人工处理。 */
    MANUAL((short) 90, "人工处理");

    private final short code;
    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;

    public static O2OPaymentStatus fromCode(short code) {
        for (O2OPaymentStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown O2O payment status: " + code);
    }
}
