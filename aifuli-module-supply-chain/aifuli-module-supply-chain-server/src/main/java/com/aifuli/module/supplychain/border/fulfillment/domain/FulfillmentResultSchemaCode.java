package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 平台履约结果封闭 Schema 编码。
 *
 * <p>编码描述采购方最终获得的标准结果或资产结构，不得使用供应商私有报文或通用占位值代替。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d11）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum FulfillmentResultSchemaCode {
    /** 直充履约结果。 */
    DIRECT_RECHARGE_RESULT("直充履约结果"),
    /** 券码资产结果。 */
    VOUCHER_CODE_RESULT("券码资产结果"),
    /** 卡号卡密资产结果。 */
    CARD_CODE_SECRET_RESULT("卡号卡密资产结果"),
    /** 券链接资产结果。 */
    VOUCHER_URL_RESULT("券链接资产结果"),
    /** 账户绑定权益资产结果。 */
    ACCOUNT_BOUND_BENEFIT_RESULT("账户绑定权益结果");

    /** 中文名称。 */
    private final String chineseName;
}
