package com.aifuli.module.supplychain.border.instore.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 到店购退款类型，对应 {@code sc_instore_refund_case.refund_type}。
 *
 * <p>取值与 DDL CHECK 约束一致。CHANNEL_REFUND 为旅通接口⑤发起的普通渠道退款；
 * CANCEL_REFUND 为旅通接口④取消已支付流水触发的退款（已支付则直接退款）。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#5-入站契约中台-api}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InstoreRefundType {
    /** 渠道发起退款：旅通接口⑤申请退款，三者一期相等。 */
    CHANNEL_REFUND("渠道发起退款"),
    /** 取消已支付流水触发的退款：旅通接口④取消时若已支付则直接退款，三者一期相等。 */
    CANCEL_REFUND("取消已支付流水退款");

    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;
}
