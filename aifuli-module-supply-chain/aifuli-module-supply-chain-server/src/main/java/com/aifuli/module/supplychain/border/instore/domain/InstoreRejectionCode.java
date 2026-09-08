package com.aifuli.module.supplychain.border.instore.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 到店购受理/退款拒绝原因码，用于异常 Case 与回执诊断。
 *
 * <p>与 O2O 拒绝码平行但独立；到店购身份绑定走 token 反向模型，故用 TOKEN 相关码替代 CHANNEL_USER_BINDING 相关码。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#7-身份绑定-token-反向模型}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InstoreRejectionCode {
    /** 旅通 token 缺失、过期或已使用，无法解析采购商与 C 端用户。 */
    TOKEN_INVALID("旅通token缺失或已失效"),
    /** 渠道用户绑定缺失或停用，无法解析采购商与 C 端用户。 */
    CHANNEL_USER_BINDING_MISSING("渠道用户绑定缺失或停用"),
    /** 同一渠道订单号或支付流水号已存在但用户、金额、商户或指纹不一致，身份冲突。 */
    IDENTITY_CONFLICT("渠道交易身份冲突"),
    /** 渠道商户未配置 SupplierChannelBinding。 */
    SUPPLIER_CHANNEL_BINDING_NOT_FOUND("渠道商户绑定未配置"),
    /** 采购商资金账户未开户。 */
    BUYER_ACCOUNT_NOT_OPENED("采购商资金账户未开户"),
    /** 采购商可用余额不足；一期预下单不抛出，保留给二期恢复余额门槛。 */
    BUYER_BALANCE_INSUFFICIENT("采购商可用余额不足"),
    /** 退款金额超过可退金额。 */
    REFUND_AMOUNT_EXCEEDED("退款金额超过可退金额"),
    /** 同一支付流水已有活动退款，新退款须排队。 */
    REFUND_ALREADY_IN_PROGRESS("已有活动退款"),
    /** 取消已支付流水时无法创建退款 Case。 */
    CANCEL_REFUND_FAILED("取消已支付流水转退款失败"),
    /** 退款实际金额与申请金额不一致，Case 转人工处理。 */
    REFUND_AMOUNT_MISMATCH("退款实际金额与申请金额不一致");

    /** 中文名称，与异常 Case 记录语义一致。 */
    private final String chineseName;
}
