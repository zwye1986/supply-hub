package com.aifuli.module.supplychain.tradecore.catalog.domain;

import java.math.BigDecimal;

/**
 * SKU 销售建议价生成规则。
 *
 * <p>本对象只保存生成依据。非供应价规则金额保存在{@code suggested_sale_price}；供应价规则的当前结果
 * 保存在独立计算状态表，由根锁内编排和本对象在同一事务中保持一致。
 * {@link Base#SUGGESTED_SALE_PRICE} 使用修改前金额，服务端会把实际基数固化到
 * {@code basePriceSnapshotCents}，避免后续读取时形成自引用。</p>
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#4-数据模型与唯一取值入口}。</p>
 */
public record SuggestedSalePriceRule(
        Mode mode,
        Base base,
        BigDecimal percentage,
        Operator operator,
        BigDecimal operand,
        Long fixedPriceCents,
        Long basePriceSnapshotCents) {

    public static SuggestedSalePriceRule fixed(long fixedPriceCents) {
        return new SuggestedSalePriceRule(Mode.FIXED, null, null, null, null,
                fixedPriceCents, null);
    }

    public static SuggestedSalePriceRule equalMarketPrice() {
        return new SuggestedSalePriceRule(Mode.EQUAL_MARKET_PRICE, null, null, null,
                null, null, null);
    }

    public enum Mode {
        EQUAL_MARKET_PRICE,
        CUSTOM,
        FIXED
    }

    public enum Base {
        MARKET_PRICE,
        SUPPLY_PRICE,
        SUGGESTED_SALE_PRICE
    }

    /** 动态供货价规则的金额不取SKU兼容列。设计见2026-10-19建议价设计§4。 */
    public boolean usesSupplyPrice() {
        return mode == Mode.CUSTOM && base == Base.SUPPLY_PRICE;
    }

    public enum Operator {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE
    }
}
