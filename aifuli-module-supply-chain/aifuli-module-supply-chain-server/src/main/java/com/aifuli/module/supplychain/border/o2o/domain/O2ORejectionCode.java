package com.aifuli.module.supplychain.border.o2o.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * O2O 受理/退款拒绝原因码，用于异常 Case 与回执诊断。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum O2ORejectionCode {
    /** 渠道用户绑定缺失或停用，无法解析采购商与 C 端用户。 */
    CHANNEL_USER_BINDING_MISSING("渠道用户绑定缺失或停用"),
    /** 同一渠道交易号已存在但用户、金额、商户或指纹不一致，身份冲突。 */
    IDENTITY_CONFLICT("渠道交易身份冲突"),
    /** 渠道商户未配置 SupplierChannelBinding。 */
    SUPPLIER_CHANNEL_BINDING_NOT_FOUND("渠道商户绑定未配置"),
    /** 采购商资金账户未开户。 */
    BUYER_ACCOUNT_NOT_OPENED("采购商资金账户未开户"),
    /** 采购商可用余额不足；一期预下单不抛出，保留给二期恢复余额门槛。 */
    BUYER_BALANCE_INSUFFICIENT("采购商可用余额不足"),
    /** 退款金额超过可退金额。 */
    REFUND_AMOUNT_EXCEEDED("退款金额超过可退金额"),
    /** 同一详情已有活动退款，新退款须排队。 */
    REFUND_ALREADY_IN_PROGRESS("已有活动退款");

    /** 中文名称，与异常 Case 记录语义一致。 */
    private final String chineseName;
}
