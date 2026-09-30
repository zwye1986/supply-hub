package com.aifuli.module.supplychain.border.rtmart;

/**
 * 旅通接口③协议支付状态。
 *
 * <p>与中台 {@code sc_instore_payment_flow.pay_status} 不同：协议只暴露 0 待支付 / 1 支付成功 / 2 取消支付。
 * 详见 {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#81-查询支付成功-data-字段始终输出}。</p>
 */
public enum RtmartPayStatus {

    PENDING(0),
    SUCCESS(1),
    CANCELLED(2);

    private final int code;

    RtmartPayStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
