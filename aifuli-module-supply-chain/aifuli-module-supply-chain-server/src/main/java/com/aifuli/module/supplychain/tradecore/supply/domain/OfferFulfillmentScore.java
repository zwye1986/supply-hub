package com.aifuli.module.supplychain.tradecore.supply.domain;

/**
 * 合作商品履约能力评分取值。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md#2-领域不变量}。</p>
 */
public final class OfferFulfillmentScore {

    /** 新建合作商品稳定性/时效性默认分。 */
    public static final int DEFAULT = 80;

    public static final int MIN = 0;

    public static final int MAX = 100;

    /** 与 {@code sc_supplier_offer.stability_score} DDL COMMENT 同一表述。 */
    public static final String STABILITY_COMMENT = "稳定性评分，人工录入，0-100，默认80";

    /** 与 {@code sc_supplier_offer.timeliness_score} DDL COMMENT 同一表述。 */
    public static final String TIMELINESS_COMMENT = "时效性评分，人工录入，0-100，默认80";

    private OfferFulfillmentScore() {
    }

    public static boolean isValid(Integer score) {
        return score != null && score >= MIN && score <= MAX;
    }
}
