package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 标准履约受理回执状态，对应 {@code sc_fulfillment_acceptance_receipt.status}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum AcceptanceReceiptStatus {
    /** 处理中：永久业务键已占用，受理事务尚未提交终态。 */
    PROCESSING((short) 0, "处理中"),
    /** 已受理：订单、资金冻结与 Outbox 已原子提交，中台承担履约责任。 */
    ACCEPTED((short) 10, "已受理"),
    /** 可重试拒绝：条件修复后可用原业务键携带上一 receiptVersion 重试。 */
    REJECTED_RETRYABLE((short) 20, "可重试拒绝"),
    /** 最终拒绝：请求内容或契约不可修复，禁止原样重试。 */
    REJECTED_FINAL((short) 30, "最终拒绝");

    private final short code;
    /** 中文名称，与 DDL COMMENT / API 语义一致。 */
    private final String chineseName;

    public static AcceptanceReceiptStatus fromCode(short code) {
        for (AcceptanceReceiptStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown acceptance receipt status: " + code);
    }
}
