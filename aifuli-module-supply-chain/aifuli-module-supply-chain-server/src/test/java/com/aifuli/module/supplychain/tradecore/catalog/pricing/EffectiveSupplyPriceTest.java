package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferPriceMode;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最低有效供货价：只采用当前有效发布版本，不再回退身份列。
 * 设计见 {@code docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#1-核心业务规则}。
 */
class EffectiveSupplyPriceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 15, 0);

    @Test
    void offerWithoutVersionReturnsNull() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 20_000L, null),
                activeSupplier(),
                null,
                9_800L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void effectiveVersionReturnsVersionPrice() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 8_000L, null, 101L),
                activeSupplier(),
                SupplierOfferVersionDO.builder()
                        .id(101L).offerId(1L).supplierSettlementUnitPrice(18_000L)
                        .validFrom(NOW.minusDays(1)).build(),
                9_800L,
                NOW);

        assertThat(price).isEqualTo(18_000L);
    }

    @Test
    void expiredVersionReturnsNullInsteadOfFallingBack() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 8_000L, null, 102L),
                activeSupplier(),
                SupplierOfferVersionDO.builder()
                        .id(102L).offerId(1L).supplierSettlementUnitPrice(900L)
                        .validFrom(NOW.minusDays(10)).validTo(NOW.minusDays(1)).build(),
                9_800L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void futureVersionReturnsNull() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 8_000L, null, 103L),
                activeSupplier(),
                SupplierOfferVersionDO.builder()
                        .id(103L).offerId(1L).supplierSettlementUnitPrice(12_000L)
                        .validFrom(NOW.plusDays(1)).build(),
                9_800L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void versionBelongingToDifferentOfferReturnsNull() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 8_000L, null, 101L),
                activeSupplier(),
                SupplierOfferVersionDO.builder()
                        .id(101L).offerId(999L).supplierSettlementUnitPrice(18_000L)
                        .validFrom(NOW.minusDays(1)).build(),
                9_800L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void suspendedSupplierIsNotACandidate() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 9_000L, null, 101L),
                SupplierDO.builder().id(4L).status(SupplierStatus.SUSPENDED.getCode()).build(),
                SupplierOfferVersionDO.builder()
                        .id(101L).offerId(1L).supplierSettlementUnitPrice(18_000L)
                        .validFrom(NOW.minusDays(1)).build(),
                9_800L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void suspendedOfferIsNotACandidate() {
        SupplierOfferDO offer = publishedOffer(OfferPriceMode.FIXED, 9_000L, null, 101L);
        offer.setStatus(OfferStatus.SUSPENDED.getCode());
        Long price = EffectiveSupplyPrice.of(
                offer,
                activeSupplier(),
                SupplierOfferVersionDO.builder()
                        .id(101L).offerId(1L).supplierSettlementUnitPrice(18_000L)
                        .validFrom(NOW.minusDays(1)).build(),
                9_800L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void retiredOfferIsNotACandidate() {
        SupplierOfferDO retired = publishedOffer(OfferPriceMode.FIXED, 9_000L, null, 101L);
        retired.setStatus(OfferStatus.RETIRED.getCode());
        Long price = EffectiveSupplyPrice.of(
                retired,
                activeSupplier(),
                SupplierOfferVersionDO.builder()
                        .id(101L).offerId(1L).supplierSettlementUnitPrice(18_000L)
                        .validFrom(NOW.minusDays(1)).build(),
                9_800L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void zeroSettlementPriceIsACandidate() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 0L, null, 101L),
                activeSupplier(),
                SupplierOfferVersionDO.builder()
                        .id(101L).offerId(1L).supplierSettlementUnitPrice(0L)
                        .validFrom(NOW.minusDays(1)).build(),
                9_800L,
                NOW);

        assertThat(price).isZero();
    }

    private static SupplierOfferDO publishedOffer(OfferPriceMode mode, Long draftUnitPrice, Integer discountBp) {
        return publishedOffer(mode, draftUnitPrice, discountBp, null);
    }

    private static SupplierOfferDO publishedOffer(OfferPriceMode mode, Long draftUnitPrice, Integer discountBp,
                                                  Long currentVersionId) {
        return SupplierOfferDO.builder()
                .id(1L)
                .supplierId(4L)
                .status(OfferStatus.PUBLISHED.getCode())
                .priceMode(mode.name())
                .draftUnitPrice(draftUnitPrice)
                .discountBp(discountBp)
                .currentVersionId(currentVersionId)
                .build();
    }

    private static SupplierDO activeSupplier() {
        return SupplierDO.builder().id(4L).status(SupplierStatus.ACTIVE.getCode()).build();
    }
}
