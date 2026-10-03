package com.aifuli.module.supplychain.tradecore.supply.domain;

/**
 * i-b 履约就绪度三态：后端计算的派生读模型状态，不落标志列。
 *
 * <p>与合作商品商业状态 {@link OfferStatus}（PUBLISHED/SUSPENDED）正交：一条 Offer 可 PUBLISHED
 * 但仍未配置履约版本。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#67-listoffers-投影扩展m1-沿用--i-b}。</p>
 */
public enum FulfillmentStatus {
    /** 未配置：{@code current_version_id==null}，尚无可履约版本。 */
    NOT_CONFIGURED,
    /** 过期：身份列与当前版本冻结字段存在差异，须重新上架。 */
    STALE,
    /** 就绪：身份列与当前版本冻结字段全一致，可履约。 */
    READY;

    public String code() {
        return name();
    }
}
