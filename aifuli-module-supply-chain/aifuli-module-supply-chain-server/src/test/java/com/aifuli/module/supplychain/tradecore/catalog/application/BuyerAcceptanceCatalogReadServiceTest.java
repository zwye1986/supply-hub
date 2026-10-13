package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 管理查询不发布，AUTO 投影过期或来源失配不能显示可下单。见最小业务设计 §7。 */
class BuyerAcceptanceCatalogReadServiceTest {
    private final BuyerListingMapper listings = mock(BuyerListingMapper.class);
    private final BuyerListingVersionMapper versions = mock(BuyerListingVersionMapper.class);
    private final BuyerListingAvailabilityMapper projections = mock(BuyerListingAvailabilityMapper.class);
    private final BuyerSkuPricingMapper pricing = mock(BuyerSkuPricingMapper.class);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);
    private final BuyerProductDO product = BuyerProductDO.builder().id(3L).buyerId(1L).platformSkuId(2L)
            .buyerProductCode("BPD_A").build();
    private BuyerListingDO listing;
    private BuyerListingAvailabilityDO projection;
    private BuyerListingVersionDO version;

    @BeforeEach
    void fixture() {
        listing = BuyerListingDO.builder().id(4L).listingNo("LST_A").buyerId(1L).platformSkuId(2L)
                .buyerSkuCode("BPD_A").buyerProductId(3L).managementMode("AUTO_SELECTION")
                .status((short) 10).currentVersionId(5L).build();
        projection = BuyerListingAvailabilityDO.builder().listingId(4L).availability((short) 10)
                .reasonCode("CONFIRMED").basisVersion(5L)
                .basisJson("{\"listingId\":4,\"sourceBuyerPricingVersionId\":6}").evaluatedAt(now.minusSeconds(1))
                .validUntil(now.plusSeconds(20)).build();
        version = BuyerListingVersionDO.builder().id(5L).listingId(4L).sourceBuyerPricingVersionId(6L)
                .validFrom(now.minusSeconds(1)).build();
        when(clock.now()).thenReturn(now);
        when(listings.selectByBuyerAndSku(1L, 2L)).thenReturn(listing);
        when(versions.selectById(5L)).thenReturn(version);
        when(projections.selectByListingId(4L)).thenReturn(projection);
        when(pricing.selectByBuyerProductId(3L)).thenReturn(BuyerSkuPricingDO.builder().id(7L)
                .buyerProductId(3L).currentVersionId(6L).pricingStatus("READY").build());
    }

    @Test
    void validAutoProjectionUsesBpdCanonicalIdentityAndFrozenSource() throws Exception {
        var view = read();
        assertThat(field(view, "canonicalBuyerSkuCode")).isEqualTo("BPD_A");
        assertThat(field(view, "acceptanceListingNo")).isEqualTo("LST_A");
        assertThat(field(view, "availabilityStatus")).isEqualTo("AVAILABLE");
        assertThat(field(view, "sourceBuyerPricingVersionId")).isEqualTo(6L);
        verify(listings, never()).selectByListingNoForUpdate(any());
        verify(versions, never()).insert(any(BuyerListingVersionDO.class));
    }

    @Test
    void expiryAtExactBoundaryIsUnknownWithoutRenewal() throws Exception {
        projection.setValidUntil(now);
        var view = read();
        assertThat(field(view, "availabilityStatus")).isEqualTo("UNKNOWN");
        assertThat(field(view, "catalogRefreshReason")).isEqualTo("PROJECTION_EXPIRED");
        assertThat(field(view, "nextEvaluateAt")).isEqualTo(now);
    }

    @Test
    void newCurrentPriceInvalidatesOldCommercialAndProjectionSource() throws Exception {
        when(pricing.selectByBuyerProductId(3L)).thenReturn(BuyerSkuPricingDO.builder().id(7L)
                .buyerProductId(3L).currentVersionId(16L).pricingStatus("READY").build());
        var view = read();
        assertThat(field(view, "availabilityStatus")).isEqualTo("UNKNOWN");
        assertThat(field(view, "catalogRefreshReason")).isEqualTo("SOURCE_CHANGED");
        assertThat(field(view, "sourceBuyerPricingVersionId")).isEqualTo(6L);
    }

    @Test
    void manualProjectionKeepsExistingReadContractEvenWithoutAutoValidity() throws Exception {
        listing.setManagementMode("MANUAL"); listing.setBuyerProductId(null); listing.setBuyerSkuCode("MANUAL_CODE");
        projection.setValidUntil(null); projection.setBasisVersion(0L); version.setSourceBuyerPricingVersionId(null);
        var view = read();
        assertThat(field(view, "canonicalBuyerSkuCode")).isEqualTo("BPD_A");
        assertThat(field(view, "availabilityStatus")).isEqualTo("AVAILABLE");
        verifyNoInteractions(pricing);
    }

    @Test
    void missingIdentityReturnsNotEvaluatedWithoutCreatingListing() throws Exception {
        when(listings.selectByBuyerAndSku(1L, 2L)).thenReturn(null);
        var view = read();
        assertThat(field(view, "canonicalBuyerSkuCode")).isEqualTo("BPD_A");
        assertThat(field(view, "acceptanceListingNo")).isNull();
        assertThat(field(view, "catalogRefreshReason")).isEqualTo("NOT_EVALUATED");
        verifyNoInteractions(versions, projections, pricing);
    }

    @Test
    void publishedReferenceAndAutoBindingCorruptionFailClosed() throws Exception {
        listing.setBuyerProductId(999L);
        assertThatThrownBy(this::read).hasRootCauseInstanceOf(IllegalStateException.class);
        listing.setBuyerProductId(3L); when(versions.selectById(5L)).thenReturn(null);
        assertThatThrownBy(this::read).hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void commercialExpiryAlsoInvalidatesOtherwiseFreshProjection() throws Exception {
        version.setValidTo(now);
        var view = read();
        assertThat(field(view, "availabilityStatus")).isEqualTo("UNKNOWN");
        assertThat(field(view, "catalogRefreshReason")).isEqualTo("PROJECTION_EXPIRED");
    }

    @Test
    void actualBasisSourceInvalidatesEvenWhenCounterEqualsCurrentPrice() throws Exception {
        projection.setBasisVersion(6L);
        projection.setBasisJson("{\"listingId\":4,\"sourceBuyerPricingVersionId\":2}");
        projection.setValidUntil(now);
        assertThat(field(read(), "catalogRefreshReason")).isEqualTo("SOURCE_CHANGED");
        projection.setValidUntil(now.plusSeconds(20));
        assertThat(field(read(), "catalogRefreshReason")).isEqualTo("SOURCE_CHANGED");
    }

    @Test
    void missingEvaluationAndExplicitNullSourceRemainDistinct() throws Exception {
        for (String basis : new String[]{null, "{}", "{\"listingId\":4}"}) {
            projection.setBasisJson(basis);
            assertThat(field(read(), "catalogRefreshReason")).isEqualTo("NOT_EVALUATED");
        }
        projection.setBasisJson("{\"listingId\":4,\"sourceBuyerPricingVersionId\":null}");
        assertThat(field(read(), "catalogRefreshReason")).isEqualTo("SOURCE_CHANGED");
        projection.setEvaluatedAt(null);
        assertThat(field(read(), "catalogRefreshReason")).isEqualTo("NOT_EVALUATED");
    }

    @Test
    void damagedDeclaredBasisIsSystemFailureEvenWhenPricingNotReady() {
        when(pricing.selectByBuyerProductId(3L)).thenReturn(BuyerSkuPricingDO.builder()
                .buyerProductId(3L).pricingStatus("UNPRICED").build());
        for (String basis : new String[]{"[]", "null", "{", "{\"listingId\":9}",
                "{\"listingId\":4,\"sourceBuyerPricingVersionId\":\"6\"}",
                "{\"sourceBuyerPricingVersionId\":-1}", "{\"listingId\":4.0}",
                "{\"listingId\":4,\"sourceBuyerPricingVersionId\":9223372036854775808}"}) {
            projection.setBasisJson(basis);
            assertThatThrownBy(this::read).as(basis).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void priceAndLifecycleTakePriorityOverMissingEvaluation() throws Exception {
        projection.setBasisJson(null);
        when(pricing.selectByBuyerProductId(3L)).thenReturn(BuyerSkuPricingDO.builder()
                .buyerProductId(3L).pricingStatus("READY").build());
        assertThat(field(read(), "catalogRefreshReason")).isEqualTo("DEFERRED_PRICE");
        listing.setStatus(BuyerListingStatus.SUSPENDED.getCode());
        assertThat(field(read(), "catalogRefreshReason")).isEqualTo("SUSPENDED");
        listing.setStatus(BuyerListingStatus.RETIRED.getCode());
        assertThat(field(read(), "catalogRefreshReason")).isEqualTo("RETIRED");
    }

    @Test
    void draftSupplyAndTimeBoundariesDoNotImplyPublication() throws Exception {
        projection.setValidUntil(now.plusNanos(1_000_000));
        version.setValidFrom(now);
        assertThat(field(read(), "availabilityStatus")).isEqualTo("AVAILABLE");
        version.setValidFrom(now.plusNanos(1_000_000));
        assertThat(field(read(), "catalogRefreshReason")).isEqualTo("PROJECTION_EXPIRED");
        listing.setCurrentVersionId(null); listing.setStatus(BuyerListingStatus.DRAFT.getCode());
        assertThat(field(read(), "availabilityStatus")).isEqualTo("AVAILABLE");
        assertThat(field(read(), "publicationState")).isEqualTo("DRAFT");
        projection.setValidUntil(now.minusNanos(1_000_000));
        assertThat(field(read(), "catalogRefreshReason")).isEqualTo("PROJECTION_EXPIRED");
    }

    private Object read() throws Exception {
        Class<?> type = null;
        try { type = Class.forName(getClass().getPackageName() + ".BuyerAcceptanceCatalogReadService"); }
        catch (ClassNotFoundException missing) { /* 明确断言尚未实现的读服务 */ }
        assertThat(type).as("管理只读目录服务必须存在").isNotNull();
        var service = type.getConstructor(BuyerListingMapper.class, BuyerListingVersionMapper.class,
                BuyerListingAvailabilityMapper.class, BuyerSkuPricingMapper.class, SupplyChainDatabaseClock.class)
                .newInstance(listings, versions, projections, pricing, clock);
        return type.getMethod("read", BuyerProductDO.class).invoke(service, product);
    }

    private static Object field(Object view, String name) throws Exception {
        return view.getClass().getMethod(name).invoke(view);
    }
}
