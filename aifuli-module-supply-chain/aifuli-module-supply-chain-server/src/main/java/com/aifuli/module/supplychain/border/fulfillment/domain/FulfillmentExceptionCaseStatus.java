package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 履约异常处置单状态，对应 {@code sc_fulfillment_exception_case.status}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#74-fulfillmentexceptioncase-与奇那梦卡密恢复}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum FulfillmentExceptionCaseStatus {

    OPEN((short) 0, "待处置"),
    PROCESSING((short) 10, "处置中"),
    RESOLVED((short) 20, "已解决"),
    CLOSED((short) 30, "已关闭");

    private final short code;
    private final String chineseName;
}
