package com.aifuli.module.supplychain.border.fulfillment.domain;

import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 适配器协议步骤编码，写入 {@code sc_fulfillment_operation.adapter_step_code}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 * <p>华昇持久步骤与恢复 AAD 详见
 * {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#41-追加迁移}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum AdapterStepCode {
    /** 直充等默认履约步骤。 */
    SEND("直充发送"),
    /** 券码 / 卡密 / 券链接发货步骤。 */
    SEND_KM("卡密/券码发送"),
    /** 华昇原单创建；不是已交付终态。详见生产闭环详细设计 §4.1、§6.1。 */
    PLACE_ORDER("供应商下单"),
    /** 华昇同一供应商单号确认。 */
    CONFIRM_ORDER("供应商确认订单"),
    /** 华昇同一供应商单号取券。 */
    TAKE_CARD("供应商取券"),
    /** 恢复外呼的有效步骤；解 checkpoint 仍用原持久步骤的 AAD。 */
    QUERY_ORDER("供应商原单查单");

    /** 中文名称。 */
    private final String chineseName;

    /**
     * 按平台履约类型选择一期默认适配器步骤。
     */
    public static AdapterStepCode forFulfillmentType(String fulfillmentTypeCode) {
        FulfillmentType type = FulfillmentType.fromCode(fulfillmentTypeCode);
        return switch (type) {
            case VOUCHER_CODE, CARD_CODE_SECRET, VOUCHER_URL -> SEND_KM;
            case DIRECT_RECHARGE, ACCOUNT_BOUND_BENEFIT -> SEND;
        };
    }
}
