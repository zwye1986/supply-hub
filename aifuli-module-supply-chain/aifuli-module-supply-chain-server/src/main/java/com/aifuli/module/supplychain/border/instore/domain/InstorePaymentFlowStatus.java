package com.aifuli.module.supplychain.border.instore.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 到店购单笔支付流水状态，对应 {@code sc_instore_payment_flow.pay_status}。
 *
 * <p>取值与 DDL CHECK 约束一致：0待支付，10支付处理中，20已支付，30支付失败，40已取消，90人工处理。
 * 旅通接口③返回的 pay_status（0待支付/1支付成功/2取消支付）在渠道服务适配层映射为本枚举。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#5-入站契约中台-api}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InstorePaymentFlowStatus {
    /** 待支付：建单后、收银台拉起前。 */
    PENDING((short) 0, "待支付"),
    /** 支付处理中：收银台已拉起。 */
    PROCESSING((short) 10, "支付处理中"),
    /** 已支付：收银台回调确认成功。 */
    PAID((short) 20, "已支付"),
    /** 支付失败。 */
    FAILED((short) 30, "支付失败"),
    /** 已取消：取消支付流水，未支付则关单，已支付则转退款 Case。 */
    CANCELLED((short) 40, "已取消"),
    /** 人工处理。 */
    MANUAL((short) 90, "人工处理");

    private final short code;
    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;

    public static InstorePaymentFlowStatus fromCode(short code) {
        for (InstorePaymentFlowStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown instore payment flow status: " + code);
    }
}
