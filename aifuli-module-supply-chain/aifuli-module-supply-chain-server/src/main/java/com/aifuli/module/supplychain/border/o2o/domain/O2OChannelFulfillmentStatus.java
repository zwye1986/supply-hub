package com.aifuli.module.supplychain.border.o2o.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * O2O 渠道履约状态，对应 {@code sc_o2o_channel_detail.channel_fulfillment_status}。
 *
 * <p>本期 225 消息暂不接入，该字段保留但中台不主动驱动。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.3-本期范围裁剪决策}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum O2OChannelFulfillmentStatus {
    /** 待支付。 */
    WAIT_PAY((short) 0, "待支付"),
    /** 处理中。 */
    PROCESSING((short) 10, "处理中"),
    /** 待取餐或待使用。 */
    READY((short) 20, "待取餐或待使用"),
    /** 已完成。 */
    COMPLETED((short) 30, "已完成"),
    /** 已取消。 */
    CANCELLED((short) 40, "已取消"),
    /** 失败。 */
    FAILED((short) 50, "失败"),
    /** 人工处理。 */
    MANUAL((short) 90, "人工处理");

    private final short code;
    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;

    public static O2OChannelFulfillmentStatus fromCode(short code) {
        for (O2OChannelFulfillmentStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown O2O channel fulfillment status: " + code);
    }
}
