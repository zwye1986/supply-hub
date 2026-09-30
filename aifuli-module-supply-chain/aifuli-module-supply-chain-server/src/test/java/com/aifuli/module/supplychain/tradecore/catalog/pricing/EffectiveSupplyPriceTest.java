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
 * 最低有效供货价：当前报价版本优先，无有效版本时回退合作商品身份列。
 * 设计见 {@code docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md}。
 */
class EffectiveSupplyPriceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 15, 0);

    @Test
    void fixedIdentityPriceIsUsedWhenOfferHasNoVersion() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 20_000L, null),
                activeSupplier(),
                null,
                9_800L,
                NOW);

        assertThat(price).isEqualTo(20_000L);
    }

    @Test
    void discountIdentityPriceRoundsHalfUpToCents() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.DISCOUNT, null, 9_000),
                activeSupplier(),
                null,
                2_014L,
                NOW);

        assertThat(price).isEqualTo(1_813L);
    }

    @Test
    void realtimeIdentityIsNotACandidate() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.REALTIME, 20_000L, null),
                activeSupplier(),
                null,
                9_800L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void effectiveVersionBeatsCheaperIdentityPrice() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 8_000L, null, 101L),
                activeSupplier(),
                SupplierOfferVersionDO.builder()
                        .id(101L).supplierSettlementUnitPrice(18_000L)
                        .validFrom(NOW.minusDays(1)).build(),
                9_800L,
                NOW);

        assertThat(price).isEqualTo(18_000L);
    }

    @Test
    void expiredVersionFallsBackToFixedIdentity() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 8_000L, null, 102L),
                activeSupplier(),
                SupplierOfferVersionDO.builder()
                        .id(102L).supplierSettlementUnitPrice(900L)
                        .validFrom(NOW.minusDays(10)).validTo(NOW.minusDays(1)).build(),
                9_800L,
                NOW);

        assertThat(price).isEqualTo(8_000L);
    }

    @Test
    void suspendedSupplierIsNotACandidate() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 9_000L, null),
                SupplierDO.builder().id(4L).status(SupplierStatus.SUSPENDED.getCode()).build(),
                null,
                9_800L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void retiredOfferIsNotACandidate() {
        SupplierOfferDO retired = publishedOffer(OfferPriceMode.FIXED, 9_000L, null);
        retired.setStatus(OfferStatus.RETIRED.getCode());
        Long price = EffectiveSupplyPrice.of(
                retired,
                activeSupplier(),
                null,
                9_800L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void discountWithoutPositiveSuggestedPriceIsNotACandidate() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.DISCOUNT, null, 9_000),
                activeSupplier(),
                null,
                0L,
                NOW);

        assertThat(price).isNull();
    }

    @Test
    void zeroFixedIdentityPriceIsACandidate() {
        Long price = EffectiveSupplyPrice.of(
                publishedOffer(OfferPriceMode.FIXED, 0L, null),
                activeSupplier(),
                null,
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
