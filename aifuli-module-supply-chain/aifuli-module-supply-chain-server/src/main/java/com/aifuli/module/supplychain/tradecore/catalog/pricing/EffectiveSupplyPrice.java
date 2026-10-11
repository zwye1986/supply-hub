package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 最低有效供货价纯计算工具：当前已发布履约版本优先，废除身份列回退。
 * 合作商品未上架或无有效已发布版本时不作为交易定价候选，返回 {@code null}。
 * 设计见 {@code docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#1-核心业务规则}。
 */
public final class EffectiveSupplyPrice {

    private EffectiveSupplyPrice() {
    }

    /**
     * 单条报价的有效供货价，单位 CNY 分。不是候选时返回 {@code null}；0 是有效价格。
     */
    public static Long of(SupplierOfferDO offer, SupplierDO supplier, SupplierOfferVersionDO version,
                          Long suggestedSalePriceCents, LocalDateTime now) {
        return of(offer, supplier, version, now);
    }

    /**
     * 单条报价的有效供货价（无兼容参数版本）。
     */
    public static Long of(SupplierOfferDO offer, SupplierDO supplier, SupplierOfferVersionDO version,
                          LocalDateTime now) {
        if (!publishedAndSupplierActive(offer, supplier)) {
            return null;
        }
        if (version == null || offer.getCurrentVersionId() == null
                || !Objects.equals(offer.getCurrentVersionId(), version.getId())
                || !Objects.equals(offer.getId(), version.getOfferId())) {
            return null;
        }
        return versionPriceIfEffective(version, now);
    }

    private static boolean publishedAndSupplierActive(SupplierOfferDO offer, SupplierDO supplier) {
        return offer != null && offer.getStatus() != null
                && offer.getStatus() == OfferStatus.PUBLISHED.getCode()
                && supplier != null && supplier.getStatus() != null
                && supplier.getStatus() == SupplierStatus.ACTIVE.getCode()
                && Objects.equals(offer.getSupplierId(), supplier.getId());
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
}
