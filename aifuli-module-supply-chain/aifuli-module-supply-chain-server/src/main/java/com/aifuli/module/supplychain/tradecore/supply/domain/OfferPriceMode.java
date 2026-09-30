package com.aifuli.module.supplychain.tradecore.supply.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 合作商品供应价模式。
 * 管理端「新增供应商品」下拉选项由 system 数据字典 {@code supply_chain_offer_price_mode} 下发；
 * 运行时仍以本枚举做封闭校验，不查询 system 字典表。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#25-供应价模式-offerpricemode}。
 */
@Getter
@RequiredArgsConstructor
public enum OfferPriceMode {
    FIXED("固定值"),
    REALTIME("实时更新"),
    DISCOUNT("按供货折扣");

    private final String chineseName;

    public static OfferPriceMode fromCode(String code) {
        for (OfferPriceMode value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown offer price mode: " + code);
    }
}
