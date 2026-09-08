package com.aifuli.module.supplychain.border.rtmart;

/**
 * 旅通建单协议的支付单类型。
 *
 * <p>当前接入范围只开放线下扫码购；后续如需启用动态码，必须先补齐收银台契约与敏感字段传递设计。
 * 详见 {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#3-动态码传递本轮延期}。</p>
 */
public enum RtmartOrderType {

    OFFLINE_SCAN_PURCHASE(1);

    private final int code;

    RtmartOrderType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean matches(int value) {
        return code == value;
    }
}
