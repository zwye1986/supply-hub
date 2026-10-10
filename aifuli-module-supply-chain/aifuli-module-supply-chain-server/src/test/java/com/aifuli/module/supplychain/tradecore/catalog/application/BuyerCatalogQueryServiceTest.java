package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogItemRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogPageReqDTO;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerCatalogChangeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.read.BuyerCatalogReadMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.readmodel.BuyerCatalogReadRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购商目录对非 READY 定价身份标记不可用；无身份行保持历史 UNKNOWN。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#1-范围与不变量}。
 */
class BuyerCatalogQueryServiceTest {

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final BuyerResolver buyerResolver = mock(BuyerResolver.class);
    private final BuyerCatalogReadMapper readMapper = mock(BuyerCatalogReadMapper.class);
    private final BuyerCatalogChangeMapper changeMapper = mock(BuyerCatalogChangeMapper.class);
    private BuyerCatalogQueryService service;

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(LocalDateTime.of(2026, 9, 16, 12, 0));
        when(clock.toInstant(any(LocalDateTime.class))).thenReturn(Instant.parse("2026-09-16T04:00:00Z"));
        when(buyerResolver.resolve(eq("AIFULI_MALL"), eq("tenant-wusheng"), any()))
                .thenReturn(new BuyerResolver.BuyerSnapshot(9L, "BYR_A", "吾盛", "吾盛", 1));
        service = new BuyerCatalogQueryService(clock, buyerResolver, readMapper, changeMapper);
    }

    @Test
    void overlaysNonReadyPricingAndKeepsLegacyUnknownWithoutIdentity() {
        BuyerCatalogReadRow unpriced = row(ListingAvailability.AVAILABLE.getCode(), PricingStatus.UNPRICED.name());
        BuyerCatalogReadRow ready = row(ListingAvailability.AVAILABLE.getCode(), PricingStatus.READY.name());
        BuyerCatalogReadRow legacy = row(null, null);
        when(readMapper.selectPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), any(), anyLong(), anyInt()))
                .thenReturn(List.of(unpriced, ready, legacy));
        when(readMapper.countPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), any()))
                .thenReturn(3L);

        BuyerCatalogPageReqDTO request = pageRequest(false);
        List<BuyerCatalogItemRespDTO> items = service.pageCatalog(request).getList();
        assertThat(items).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNAVAILABLE.name(),
                        ListingAvailability.AVAILABLE.name(),
                        ListingAvailability.UNKNOWN.name());
    }

    @Test
    void autoSelectionExpiredProjectionOverlaysToUnknown() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 12, 0);
        BuyerCatalogReadRow expired = row(ListingAvailability.AVAILABLE.getCode(), PricingStatus.READY.name());
        expired.setManagementMode("AUTO_SELECTION");
        expired.setSourceBuyerPricingVersionId(101L);
        expired.setCurrentPricingVersionId(101L);
        expired.setValidUntil(now.minusMinutes(1)); // 已过期

        when(readMapper.selectPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(now), anyLong(), anyInt()))
                .thenReturn(List.of(expired));
        when(readMapper.countPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(now)))
                .thenReturn(1L);

        List<BuyerCatalogItemRespDTO> items = service.pageCatalog(pageRequest(false)).getList();
        assertThat(items).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNKNOWN.name());
    }

    @Test
    void autoSelectionMismatchedPricingSourceOverlaysToUnknown() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 12, 0);
        BuyerCatalogReadRow mismatched = row(ListingAvailability.AVAILABLE.getCode(), PricingStatus.READY.name());
        mismatched.setManagementMode("AUTO_SELECTION");
        mismatched.setSourceBuyerPricingVersionId(101L);
        mismatched.setCurrentPricingVersionId(102L); // 来源失配
        mismatched.setValidUntil(now.plusHours(1));

        when(readMapper.selectPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(now), anyLong(), anyInt()))
                .thenReturn(List.of(mismatched));
        when(readMapper.countPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(now)))
                .thenReturn(1L);

        List<BuyerCatalogItemRespDTO> items = service.pageCatalog(pageRequest(false)).getList();
        assertThat(items).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNKNOWN.name());
    }

    @Test
    void manualModeKeepsAvailableEvenIfValidUntilExpiredOrPricingSourceMissing() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 12, 0);
        BuyerCatalogReadRow manual = row(ListingAvailability.AVAILABLE.getCode(), PricingStatus.READY.name());
        manual.setManagementMode("MANUAL");
        manual.setSourceBuyerPricingVersionId(null);
        manual.setValidUntil(now.minusMinutes(1));

        when(readMapper.selectPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(now), anyLong(), anyInt()))
                .thenReturn(List.of(manual));
        when(readMapper.countPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(now)))
                .thenReturn(1L);

        List<BuyerCatalogItemRespDTO> items = service.pageCatalog(pageRequest(false)).getList();
        assertThat(items).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.AVAILABLE.name());
    }

    @Test
    void availableOnlyPassesReadyPricingStatusAndNowToReadModel() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 12, 0);
        BuyerCatalogReadRow ready = row(ListingAvailability.AVAILABLE.getCode(), PricingStatus.READY.name());
        when(readMapper.selectPage(eq(9L), any(), any(), eq(true), eq(PricingStatus.READY.name()), eq(now), anyLong(), anyInt()))
                .thenReturn(List.of(ready));
        when(readMapper.countPage(eq(9L), any(), any(), eq(true), eq(PricingStatus.READY.name()), eq(now)))
                .thenReturn(1L);

        assertThat(service.pageCatalog(pageRequest(true)).getList())
                .extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.AVAILABLE.name());
        verify(readMapper).selectPage(eq(9L), any(), any(), eq(true), eq(PricingStatus.READY.name()), eq(now), anyLong(), anyInt());
        verify(readMapper).countPage(eq(9L), any(), any(), eq(true), eq(PricingStatus.READY.name()), eq(now));
    }

    private static BuyerCatalogPageReqDTO pageRequest(boolean availableOnly) {
        BuyerCatalogPageReqDTO request = new BuyerCatalogPageReqDTO();
        request.setSourceSystem("AIFULI_MALL");
        request.setExternalTenantId("tenant-wusheng");
        request.setPageNo(1);
        request.setPageSize(20);
        request.setAvailableOnly(availableOnly);
        return request;
    }

    private static BuyerCatalogReadRow row(Short availability, String pricingStatus) {
        BuyerCatalogReadRow row = new BuyerCatalogReadRow();
        row.setBuyerSkuCode("WS-TELCO-100");
        row.setListingStatus(BuyerListingStatus.PUBLISHED.getCode());
        row.setListingVersion(1);
        row.setPlatformSkuCode("TELCO_100");
        row.setPlatformSpuCode("TELCO");
        row.setDisplayName("话费");
        row.setBrandCode("CMCC");
        row.setBrandName("移动");
        row.setCategoryCode("RECHARGE");
        row.setCategoryName("直充");
        row.setFulfillmentType("DIRECT_RECHARGE");
        row.setBuyerSettlementUnitPrice(9800L);
        row.setCurrency("CNY");
        row.setAvailability(availability);
        row.setAvailabilityVersion(1L);
        row.setPricingStatus(pricingStatus);
        row.setUpdatedAt(LocalDateTime.of(2026, 9, 16, 12, 0));
        return row;
    }
}
