package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogRefreshReason;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 管理端与 RPC 共用的 AUTO 只读决策，不查询、不写入、不刷新、不授权项目下单。
 * 计数不参与定价身份比较；完整性先于业务覆盖，真实来源变化先于过期原因。
 * MANUAL 调用方保持原契约，不进入本策略。
 * 设计见 {@code docs/superpowers/specs/2026-10-12-catalog-source-freshness-correction-design.md#4-auto-判定顺序与时间边界}。
 */
public final class BuyerCatalogAutoReadPolicy {
    private BuyerCatalogAutoReadPolicy() { }

    public static Decision decide(Input input, LocalDateTime now) {
        var basis = BuyerAvailabilityBasis.readPricingSource(input.basisJson(), input.listingId());
        if (input.state() == BuyerListingStatus.SUSPENDED) return unknown(CatalogRefreshReason.SUSPENDED);
        if (input.state() == BuyerListingStatus.RETIRED) return unknown(CatalogRefreshReason.RETIRED);
        if (!PricingStatus.READY.name().equals(input.pricingStatus()) || input.currentPricingVersionId() == null) {
            return unknown(CatalogRefreshReason.DEFERRED_PRICE);
        }
        if (!basis.verifiable() || input.evaluatedAt() == null) return unknown(CatalogRefreshReason.NOT_EVALUATED);
        if (!Objects.equals(basis.sourceBuyerPricingVersionId(), input.currentPricingVersionId())
                || input.commercialPresent() && !Objects.equals(input.commercialSourceVersionId(), input.currentPricingVersionId())) {
            return unknown(CatalogRefreshReason.SOURCE_CHANGED);
        }
        if (input.validUntil() == null || !input.validUntil().isAfter(now)
                || input.commercialPresent() && (input.commercialValidFrom() == null || input.commercialValidFrom().isAfter(now)
                || input.commercialValidTo() != null && !input.commercialValidTo().isAfter(now))) {
            return unknown(CatalogRefreshReason.PROJECTION_EXPIRED);
        }
        return new Decision(input.storedAvailability(), input.storedReason());
    }

    private static Decision unknown(CatalogRefreshReason reason) {
        return new Decision(ListingAvailability.UNKNOWN, reason.name());
    }

    /** 同一供应链读视图取得的来源与期限快照；不接收 basisVersion，防止计数再次混用。 */
    public record Input(Long listingId, BuyerListingStatus state, String pricingStatus, Long currentPricingVersionId,
                        String basisJson, LocalDateTime evaluatedAt, LocalDateTime validUntil,
                        boolean commercialPresent, Long commercialSourceVersionId,
                        LocalDateTime commercialValidFrom, LocalDateTime commercialValidTo,
                        ListingAvailability storedAvailability, String storedReason) { }

    public record Decision(ListingAvailability availability, String reason) { }
}
