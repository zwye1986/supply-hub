package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferPriceMode;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 最低有效供货价：当前报价版本优先，无有效版本时回退合作商品身份列。
 * 商品列表、标品 SKU 查询与通用定价必须共用本解析，禁止各写一套。
 * 设计见 {@code docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md}。
 */
public final class EffectiveSupplyPrice {

    private static final int DISCOUNT_BP_SCALE = 10_000;

    private EffectiveSupplyPrice() {
    }

    /**
     * 单条报价的有效供货价，单位 CNY 分。不是候选时返回 {@code null}；0 是有效价格。
     */
    public static Long of(SupplierOfferDO offer, SupplierDO supplier, SupplierOfferVersionDO version,
                          Long suggestedSalePriceCents, LocalDateTime now) {
        if (!publishedAndSupplierActive(offer, supplier)) {
            return null;
        }
        Long versionPrice = versionPriceIfEffective(version, now);
        if (versionPrice != null) {
            return versionPrice;
        }
        return identityPrice(offer, suggestedSalePriceCents);
    }

    private static boolean publishedAndSupplierActive(SupplierOfferDO offer, SupplierDO supplier) {
        return offer != null && offer.getStatus() != null
                && offer.getStatus() == OfferStatus.PUBLISHED.getCode()
                && supplier != null && supplier.getStatus() != null
                && supplier.getStatus() == SupplierStatus.ACTIVE.getCode();
    }

    private static Long versionPriceIfEffective(SupplierOfferVersionDO version, LocalDateTime now) {
        if (version == null || version.getValidFrom() == null || now == null
                || version.getSupplierSettlementUnitPrice() == null
                || version.getValidFrom().isAfter(now)) {
            return null;
        }
        if (version.getValidTo() != null && !version.getValidTo().isAfter(now)) {
            return null;
        }
        return version.getSupplierSettlementUnitPrice();
    }

    private static Long identityPrice(SupplierOfferDO offer, Long suggestedSalePriceCents) {
        if (offer.getPriceMode() == null) {
            return null;
        }
        OfferPriceMode mode;
        try {
            mode = OfferPriceMode.fromCode(offer.getPriceMode());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return switch (mode) {
            case FIXED -> offer.getDraftUnitPrice();
            case DISCOUNT -> discountPrice(suggestedSalePriceCents, offer.getDiscountBp());
            case REALTIME -> null;
        };
    }

    private static Long discountPrice(Long suggestedSalePriceCents, Integer discountBp) {
        if (suggestedSalePriceCents == null || suggestedSalePriceCents <= 0
                || discountBp == null || discountBp < 1 || discountBp > DISCOUNT_BP_SCALE) {
            return null;
        }
        return BigDecimal.valueOf(suggestedSalePriceCents)
                .multiply(BigDecimal.valueOf(discountBp))
                .divide(BigDecimal.valueOf(DISCOUNT_BP_SCALE), 0, RoundingMode.HALF_UP)
                .longValue();
    }
}
