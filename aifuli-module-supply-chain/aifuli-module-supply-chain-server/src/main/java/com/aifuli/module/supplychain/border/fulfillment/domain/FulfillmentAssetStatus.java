package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 履约交付资产状态，对应 {@code sc_fulfillment_asset.lifecycle_status}。
 *
 * <p>详见详细设计 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum FulfillmentAssetStatus {

    /** 可交付（已签发未向终端交付）。 */
    AVAILABLE((short) 10, "可交付"),
    /** 已交付（已向终端用户或采购方暴露）。 */
    DELIVERED((short) 20, "已交付"),
    /** 已作废（退款/冲正后作废）。 */
    VOIDED((short) 30, "已作废"),
    /** 已解绑（权益卡解绑）。 */
    UNBOUND((short) 40, "已解绑"),
    /** 已过期。 */
    EXPIRED((short) 50, "已过期"),
    /** 隔离状态，不可向采购方交付。 */
    QUARANTINED((short) 90, "隔离不可交付");

    private final short code;
    private final String chineseName;

    public static FulfillmentAssetStatus fromCode(short code) {
        for (FulfillmentAssetStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown fulfillment asset status: " + code);
    }
}
