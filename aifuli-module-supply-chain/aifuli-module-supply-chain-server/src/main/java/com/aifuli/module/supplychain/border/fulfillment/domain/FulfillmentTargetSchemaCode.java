package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 平台履约目标封闭 Schema 编码。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum FulfillmentTargetSchemaCode {
    /** 直充目标：账号类型 + 账号值。 */
    DIRECT_RECHARGE_TARGET("直充目标"),
    /** 账户绑定权益目标：账号类型 + 账号值。 */
    ACCOUNT_BOUND_BENEFIT_TARGET("账户绑定权益目标"),
    /** 券类交付目标：交付模式。 */
    VOUCHER_DELIVERY_TARGET("券类交付目标");

    /** 中文名称。 */
    private final String chineseName;
}
