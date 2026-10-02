package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 外部直充发送阶段及其持久化编码，用于判断待发送意图是否仍可安全撤销。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#5-结果窗口和资金}。
 */
@Getter
@RequiredArgsConstructor
public enum RechargeFrequencyDispatchPhase {

    PREPARED((short) 0, "已准备，尚未进入发送窗口"),
    SEND_POSSIBLE((short) 10, "可能已经发送至外部渠道");

    private final short code;
    private final String chineseName;
}
