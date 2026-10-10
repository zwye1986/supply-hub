package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 单目录锁序、来源与幂等发布，见最小业务设计 §4。真实事务另由 IntegrationTest 验证。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BuyerSelectionCatalogLifecycleTest {
    @Mock SupplyChainDatabaseClock clock;
    @Mock BuyerListingMapper listings;
    @Mock BuyerListingVersionMapper versions;
    @Mock BuyerListingAvailabilityMapper availability;
    @Mock BuyerCatalogChangeMapper changes;
    @Mock BuyerProductMapper products;
    @Mock BuyerSkuPricingMapper pricing;
    @Mock BuyerSkuPricingVersionMapper priceVersions;
    @Mock PlatformSkuMapper skus;
    @Mock CurrentSupplyEligibilityReader supply;
    @org.mockito.Spy ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @InjectMocks BuyerSelectionCatalogLifecycle lifecycle;
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);
    private BuyerListingDO listing;

    @BeforeEach
    void fixture() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BuyerListingDO.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BuyerListingAvailabilityDO.class);
        listing = BuyerListingDO.builder().id(1L).listingNo("LST_TEST").buyerId(2L).platformSkuId(3L)
                .buyerProductId(4L).buyerSkuCode("BPD_TEST").managementMode("AUTO_SELECTION")
                .status(BuyerListingStatus.DRAFT.getCode()).version(0L).build();
        when(listings.selectByListingNoForUpdate("LST_TEST")).thenReturn(listing);
        when(clock.now()).thenReturn(now);
        when(products.selectByIdForUpdate(4L)).thenReturn(BuyerProductDO.builder().id(4L).buyerId(2L)
                .platformSkuId(3L).buyerProductCode("BPD_TEST").status((short) 10).build());
        when(pricing.selectByBuyerProductIdForUpdate(4L)).thenReturn(BuyerSkuPricingDO.builder()
                .id(5L).buyerProductId(4L).currentVersionId(6L).pricingStatus("READY").version(0L).build());
        when(priceVersions.selectById(6L)).thenReturn(BuyerSkuPricingVersionDO.builder().id(6L).pricingId(5L)
                .versionNo(1).distributionPriceCents(123L).currency("CNY").pricingStatus("READY").publishedBy(9L).build());
        when(skus.selectById(3L)).thenReturn(PlatformSkuDO.builder().id(3L).skuName("真实商品").build());
        when(supply.evaluateBuyerSelection(2L, 4L, 3L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.AVAILABLE, "CONFIRMED", now, null));
        when(availability.selectByListingId(1L)).thenReturn(BuyerListingAvailabilityDO.builder().id(7L)
                .listingId(1L).availability((short) 30).version(0L).build());
        when(listings.update(isNull(), any())).thenReturn(1);
        when(availability.update(isNull(), any())).thenReturn(1);
        when(versions.insert(any(BuyerListingVersionDO.class))).thenAnswer(call -> {
            call.getArgument(0, BuyerListingVersionDO.class).setId(8L); return 1;
        });
    }

    @Test
    void manualDoesNotTouchSharedProjectionOrPrice() {
        listing.setManagementMode("MANUAL"); listing.setBuyerProductId(null);
        assertThat(lifecycle.refreshListing("LST_TEST", 9L).reason()).isEqualTo("MANUAL");
        verifyNoInteractions(products, pricing, versions, availability, changes, supply);
    }

    @Test
    void suspendedAndRetiredNeverAutoResume() {
        listing.setStatus(BuyerListingStatus.SUSPENDED.getCode());
        assertThat(lifecycle.refreshListing("LST_TEST", 9L).reason()).isEqualTo("SUSPENDED");
        listing.setStatus(BuyerListingStatus.RETIRED.getCode());
        assertThat(lifecycle.refreshListing("LST_TEST", 9L).reason()).isEqualTo("RETIRED");
        verifyNoInteractions(products, pricing, versions, availability, changes, supply);
    }

    @Test
    void publishesOnlyRealReadyDistributionPriceWithBoundedProjection() {
        var result = lifecycle.refreshListing("LST_TEST", 9L);
        assertThat(result.publicationState()).isEqualTo("PUBLISHED");
        assertThat(result.sourceBuyerPricingVersionId()).isEqualTo(6L);
        assertThat(result.nextEvaluateAt()).isEqualTo(now.plusSeconds(30));
        var capture = org.mockito.ArgumentCaptor.forClass(BuyerListingVersionDO.class);
        verify(versions).insert(capture.capture());
        assertThat(capture.getValue().getBuyerSettlementUnitPrice()).isEqualTo(123L);
        assertThat(capture.getValue().getSourceBuyerPricingVersionId()).isEqualTo(6L);
        assertThat(capture.getValue().getPublishedBy()).isEqualTo(9L);
        var ordered = inOrder(listings, products, pricing, supply, versions, changes);
        ordered.verify(listings).selectByListingNoForUpdate("LST_TEST");
        ordered.verify(products).selectByIdForUpdate(4L);
        ordered.verify(pricing).selectByBuyerProductIdForUpdate(4L);
        ordered.verify(supply).evaluateBuyerSelection(2L, 4L, 3L);
        ordered.verify(versions).insert(any(BuyerListingVersionDO.class));
        ordered.verify(changes).insert(any(BuyerCatalogChangeDO.class));
    }

    @Test
    void unknownSupplyCannotPublishDraft() {
        when(supply.evaluateBuyerSelection(2L, 4L, 3L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.UNKNOWN, "SELECTED_SUPPLY_UNKNOWN", now, null));
        assertThat(lifecycle.refreshListing("LST_TEST", 9L).publicationState()).isEqualTo("DRAFT");
        verify(versions, never()).insert(any(BuyerListingVersionDO.class));
    }

    @Test
    void finalAdmissionUsesCurrentLockedPriceAndWritesNothing() {
        var current = publishAndRetain();
        clearInvocations(pricing, priceVersions, versions, products, changes, availability, supply);
        var source = priceVersions.selectById(6L);
        when(priceVersions.selectByIdForUpdate(6L)).thenReturn(source);
        lifecycle.requireCurrentAutoAdmission(listing, current);
        verify(pricing).selectByBuyerProductIdForUpdate(4L);
        verify(priceVersions).selectByIdForUpdate(6L);
        verify(products).selectByIdForUpdate(4L);
        verifyNoInteractions(changes, availability, supply);
    }

    @Test
    void finalAdmissionRejectsCurrentNonReadyPrice() {
        var current = publishAndRetain();
        var frozenSource = priceVersions.selectById(6L);
        when(priceVersions.selectByIdForUpdate(6L)).thenReturn(frozenSource);
        when(pricing.selectByBuyerProductIdForUpdate(4L)).thenReturn(BuyerSkuPricingDO.builder()
                .id(5L).buyerProductId(4L).pricingStatus("UNPRICED").build());
        assertThatThrownBy(() -> lifecycle.requireCurrentAutoAdmission(listing, current))
                .isInstanceOf(com.aifuli.module.supplychain.tradecore.catalog.pricing.ProductNotSaleableException.class);
    }

    @Test
    void finalAdmissionRejectsChangedReadySourceWithoutRefreezingPrice() {
        var current = publishAndRetain();
        var frozenSource = priceVersions.selectById(6L);
        when(priceVersions.selectByIdForUpdate(6L)).thenReturn(frozenSource);
        when(pricing.selectByBuyerProductIdForUpdate(4L)).thenReturn(BuyerSkuPricingDO.builder()
                .id(5L).buyerProductId(4L).currentVersionId(16L).pricingStatus("READY").build());
        when(priceVersions.selectByIdForUpdate(16L)).thenReturn(BuyerSkuPricingVersionDO.builder()
                .id(16L).pricingId(5L).versionNo(2).distributionPriceCents(456L).currency("CNY").pricingStatus("READY").build());
        assertThatThrownBy(() -> lifecycle.requireCurrentAutoAdmission(listing, current))
                .isInstanceOf(com.aifuli.module.supplychain.tradecore.catalog.pricing.ProductNotSaleableException.class);
    }

    @Test
    void finalAdmissionDanglingFrozenSourceIsSystemFailureEvenWhenCurrentPriceChanged() {
        var current = publishAndRetain();
        when(pricing.selectByBuyerProductIdForUpdate(4L)).thenReturn(BuyerSkuPricingDO.builder()
                .id(5L).buyerProductId(4L).currentVersionId(16L).pricingStatus("READY").build());
        when(priceVersions.selectByIdForUpdate(16L)).thenReturn(BuyerSkuPricingVersionDO.builder()
                .id(16L).pricingId(5L).versionNo(2).distributionPriceCents(456L).currency("CNY").pricingStatus("READY").build());
        assertThatThrownBy(() -> lifecycle.requireCurrentAutoAdmission(listing, current))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(com.aifuli.module.supplychain.tradecore.catalog.pricing.ProductNotSaleableException.class);
    }

    @Test
    void finalAdmissionBrokenCurrentSourceIsSystemFailure() {
        var current = publishAndRetain();
        assertThatThrownBy(() -> lifecycle.requireCurrentAutoAdmission(listing, current))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(com.aifuli.module.supplychain.tradecore.catalog.pricing.ProductNotSaleableException.class);
    }

    @Test
    void finalAdmissionDamagedCommercialDigestIsSystemFailure() {
        var current = publishAndRetain();
        current.setCommercialSnapshotDigest(new byte[32]);
        assertThatThrownBy(() -> lifecycle.requireCurrentAutoAdmission(listing, current))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(com.aifuli.module.supplychain.tradecore.catalog.pricing.ProductNotSaleableException.class);
    }

    @Test
    void actualMissingPriceDefersWithoutPublishing() {
        when(pricing.selectByBuyerProductIdForUpdate(4L)).thenReturn(null);
        assertThat(lifecycle.refreshListing("LST_TEST", 9L).reason()).isEqualTo("DEFERRED_PRICE");
        verifyNoInteractions(supply, versions);
    }

    @Test
    void brokenReadyPriceIsSystemFailureRatherThanDeferred() {
        when(priceVersions.selectById(6L)).thenReturn(null);
        assertThatThrownBy(() -> lifecycle.refreshListing("LST_TEST", 9L)).isInstanceOf(IllegalStateException.class);
        verify(changes, never()).insert(any(BuyerCatalogChangeDO.class));
    }

    @Test
    void httpPublicationCannotInventOperator() {
        BuyerSkuPricingVersionDO source = priceVersions.selectById(6L);
        source.setPublishedBy(null);
        assertThatThrownBy(() -> lifecycle.refreshListing("LST_TEST", null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit");
    }

    @Test
    void unchangedContentDoesNotCreateAnotherCommercialVersionOrCursor() {
        var first = publishAndRetain();
        when(supply.evaluateBuyerSelection(2L, 4L, 3L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.AVAILABLE, "CONFIRMED", now.plusSeconds(5), null));
        lifecycle.refreshListing("LST_TEST", null);
        verify(versions, times(1)).insert(any(BuyerListingVersionDO.class));
        verify(changes, times(1)).insert(any(BuyerCatalogChangeDO.class));
        assertThat(first.getValidFrom()).isEqualTo(now);
    }

    @Test
    void newSourceUsesNewPublicationTimeInsteadOfBackdatingOldStart() {
        publishAndRetain();
        var source = priceVersions.selectById(6L);
        source.setId(16L);
        when(pricing.selectByBuyerProductIdForUpdate(4L)).thenReturn(BuyerSkuPricingDO.builder()
                .id(5L).buyerProductId(4L).currentVersionId(16L).pricingStatus("READY").version(1L).build());
        when(priceVersions.selectById(16L)).thenReturn(source);
        when(supply.evaluateBuyerSelection(2L, 4L, 3L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.AVAILABLE, "CONFIRMED", now.plusSeconds(5), null));
        lifecycle.refreshListing("LST_TEST", 9L);
        var capture = org.mockito.ArgumentCaptor.forClass(BuyerListingVersionDO.class);
        verify(versions, times(2)).insert(capture.capture());
        assertThat(capture.getAllValues().getLast().getValidFrom()).isEqualTo(now.plusSeconds(5));
    }

    @Test
    void damagedCommercialAmountCannotBeHiddenByMatchingDigest() {
        var first = publishAndRetain();
        first.setBuyerSettlementUnitPrice(-1L);
        assertThatThrownBy(() -> lifecycle.refreshListing("LST_TEST", 9L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishedListingWithoutCurrentIsSystemFailure() {
        listing.setStatus(BuyerListingStatus.PUBLISHED.getCode());
        assertThatThrownBy(() -> lifecycle.refreshListing("LST_TEST", 9L))
                .isInstanceOf(IllegalStateException.class);
        verify(versions, never()).insert(any(BuyerListingVersionDO.class));
    }

    @Test
    void knownSupplyBoundaryShortensProjectionLifetime() {
        when(supply.evaluateBuyerSelection(2L, 4L, 3L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.AVAILABLE, "CONFIRMED", now, null, now.plusSeconds(3)));
        assertThat(lifecycle.refreshListing("LST_TEST", 9L).nextEvaluateAt()).isEqualTo(now.plusSeconds(3));
    }

    @Test
    void nextNaturalDayShortensProjectionLifetime() {
        var beforeMidnight = now.toLocalDate().atTime(23, 59, 58);
        when(supply.evaluateBuyerSelection(2L, 4L, 3L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.AVAILABLE, "CONFIRMED", beforeMidnight, null));
        assertThat(lifecycle.refreshListing("LST_TEST", 9L).nextEvaluateAt())
                .isEqualTo(now.toLocalDate().plusDays(1).atStartOfDay());
    }

    private BuyerListingVersionDO publishAndRetain() {
        lifecycle.refreshListing("LST_TEST", 9L);
        var capture = org.mockito.ArgumentCaptor.forClass(BuyerListingVersionDO.class);
        verify(versions).insert(capture.capture());
        var first = capture.getValue();
        when(versions.selectById(8L)).thenReturn(first);
        when(versions.selectLatest(1L)).thenReturn(first);
        var cursor = org.mockito.ArgumentCaptor.forClass(BuyerCatalogChangeDO.class);
        verify(changes).insert(cursor.capture());
        when(availability.selectByListingId(1L)).thenReturn(BuyerListingAvailabilityDO.builder().id(7L)
                .listingId(1L).availability(ListingAvailability.AVAILABLE.getCode()).version(1L)
                .reasonCode("CONFIRMED").basisDigest(cursor.getValue().getPayloadDigest()).build());
        return first;
    }
}
