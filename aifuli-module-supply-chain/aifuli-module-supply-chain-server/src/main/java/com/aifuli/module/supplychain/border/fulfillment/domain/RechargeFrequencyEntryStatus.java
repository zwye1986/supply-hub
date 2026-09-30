package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 直充手机号全局占用记录状态及其持久化编码。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#1-目标与已确认决策}。
 */
@Getter
@RequiredArgsConstructor
public enum RechargeFrequencyEntryStatus {

    WAITING((short) 0, "等待前序请求"),
    HOLDING((short) 10, "正在占用"),
    FINISHED((short) 20, "已结束");

    private final short code;
    private final String chineseName;
}
