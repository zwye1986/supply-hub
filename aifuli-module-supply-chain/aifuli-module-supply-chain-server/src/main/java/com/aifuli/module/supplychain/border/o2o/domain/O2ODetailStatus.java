package com.aifuli.module.supplychain.border.o2o.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * O2O 详情聚合状态，对应 {@code sc_o2o_channel_detail.status}。
 *
 * <p>取值与 DDL CHECK 约束一致：0已创建，10处理中，20已完成，30已取消，40失败，90人工处理。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.2-O2OChannelDetail}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum O2ODetailStatus {
    /** 已创建。 */
    CREATED((short) 0, "已创建"),
    /** 处理中。 */
    PROCESSING((short) 10, "处理中"),
    /** 已完成。 */
    COMPLETED((short) 20, "已完成"),
    /** 已取消。 */
    CANCELLED((short) 30, "已取消"),
    /** 失败。 */
    FAILED((short) 40, "失败"),
    /** 人工处理。 */
    MANUAL((short) 90, "人工处理");

    private final short code;
    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;

    public static O2ODetailStatus fromCode(short code) {
        for (O2ODetailStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown O2O detail status: " + code);
    }
}
