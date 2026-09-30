package com.aifuli.module.supplychain.tradecore.catalog.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Locale;
import java.util.Optional;

/**
 * 商品中心列表页的固定业务 Tab。
 *
 * <p>普通类型 Tab 由 {@code sc_product_type} 动态生成、直接以 {@code typeCode} 过滤 {@code productType}；
 * 本枚举只收敛需要服务端复合解构（同时约束 {@code productType} 与 {@code fulfillmentType}）的固定 Tab，
 * 避免这些映射以字面量形式散落在查询服务中。</p>
 *
 * <p>此处的 {@code productType} 取值对应 {@code 028} 变更集预置的类型编码，属于 Tab 契约的一部分，
 * 不构成对动态商品类型的封闭枚举约束。</p>
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#34-列表-tab-复合解构映射与动态来源}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ProductCenterTab {
    /** 全部：不附加任何类型或履约过滤。 */
    ALL(null, null),
    /** 虚拟卡密。 */
    VIRTUAL_CARD("EQUITY_PRODUCT", FulfillmentType.CARD_CODE_SECRET),
    /** 虚拟直充。 */
    VIRTUAL_RECHARGE("EQUITY_PRODUCT", FulfillmentType.DIRECT_RECHARGE),
    /** 京东实物。 */
    JD("JD", null);

    private final String productType;
    private final FulfillmentType fulfillmentType;

    public String fulfillmentTypeCode() {
        return fulfillmentType == null ? null : fulfillmentType.code();
    }

    /** 解析固定 Tab；返回空表示该 tabCode 应按动态商品类型编码处理。 */
    public static Optional<ProductCenterTab> fromCode(String tabCode) {
        if (tabCode == null || tabCode.isBlank()) {
            return Optional.of(ALL);
        }
        String normalized = tabCode.trim().toUpperCase(Locale.ROOT);
        for (ProductCenterTab tab : values()) {
            if (tab.name().equals(normalized)) {
                return Optional.of(tab);
            }
        }
        return Optional.empty();
    }
}
