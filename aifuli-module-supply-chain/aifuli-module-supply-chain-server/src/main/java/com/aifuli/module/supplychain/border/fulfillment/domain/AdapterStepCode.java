package com.aifuli.module.supplychain.border.fulfillment.domain;

import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 适配器协议步骤编码，写入 {@code sc_fulfillment_operation.adapter_step_code}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum AdapterStepCode {
    /** 直充等默认履约步骤。 */
    SEND("直充发送"),
    /** 券码 / 卡密 / 券链接发货步骤。 */
    SEND_KM("卡密/券码发送");

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
