package com.aifuli.module.supplychain.border.o2o.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * O2O 中台资金状态，对应 {@code sc_o2o_channel_detail.fund_status}。
 *
 * <p>取值与 DDL CHECK 约束一致：0未冻结，10已冻结，20已提交，30部分返还，40已全部返还，90人工处理。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.2-O2OChannelDetail}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum O2OFundStatus {
    /** 未冻结。 */
    NONE((short) 0, "未冻结"),
    /** 已冻结：受理时按 payAmount 冻结。 */
    RESERVED((short) 10, "已冻结"),
    /** 已提交：支付确认后提交 FundReservation。 */
    COMMITTED((short) 20, "已提交"),
    /** 部分返还：分次退款后部分返还。 */
    PARTIALLY_RETURNED((short) 30, "部分返还"),
    /** 已全部返还。 */
    RETURNED((short) 40, "已全部返还"),
    /** 人工处理。 */
    MANUAL((short) 90, "人工处理");

    private final short code;
    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;

    public static O2OFundStatus fromCode(short code) {
        for (O2OFundStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown O2O fund status: " + code);
    }
}
