package com.aifuli.module.supplychain.tradecore.catalog.domain;

import java.math.BigDecimal;

/**
 * SKU 销售建议价生成规则。
 *
 * <p>{@code suggested_sale_price} 保存规则计算后的物化金额，本对象只保存生成依据。两者必须在同一事务中更新。
 * {@link Base#SUGGESTED_SALE_PRICE} 使用修改前金额，服务端会把实际基数固化到
 * {@code basePriceSnapshotCents}，避免后续读取时形成自引用。</p>
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#221-销售建议价规则039-changeset}。</p>
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
        SUGGESTED_SALE_PRICE
    }

    public enum Operator {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE
    }
}
