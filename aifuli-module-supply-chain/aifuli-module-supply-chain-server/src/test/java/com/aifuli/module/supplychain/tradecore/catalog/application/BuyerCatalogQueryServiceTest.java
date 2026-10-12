package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogItemRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogPageReqDTO;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerCatalogChangeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.read.BuyerCatalogReadMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
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
 * AUTO行遵守投影陈旧安全：来源不匹配／期限为空或不超过库时钟返回UNKNOWN；MANUAL保持旧RPC准入。
 * 设计见 §6.4「投影陈旧安全」与 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#1-范围与不变量}。
 */
class BuyerCatalogQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 12, 0);

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final BuyerResolver buyerResolver = mock(BuyerResolver.class);
    private final BuyerCatalogReadMapper readMapper = mock(BuyerCatalogReadMapper.class);
    private final BuyerCatalogChangeMapper changeMapper = mock(BuyerCatalogChangeMapper.class);
    private BuyerCatalogQueryService service;

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(NOW);
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
        when(readMapper.selectPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(NOW), anyLong(), anyInt()))
                .thenReturn(List.of(unpriced, ready, legacy));
        when(readMapper.countPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(NOW)))
                .thenReturn(3L);

        BuyerCatalogPageReqDTO request = pageRequest(false);
        List<BuyerCatalogItemRespDTO> items = service.pageCatalog(request).getList();
        assertThat(items).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNAVAILABLE.name(),
                        ListingAvailability.AVAILABLE.name(),
                        ListingAvailability.UNKNOWN.name());
    }

    @Test
    void availableOnlyPassesReadyPricingStatusToReadModel() {
        BuyerCatalogReadRow ready = row(ListingAvailability.AVAILABLE.getCode(), PricingStatus.READY.name());
        when(readMapper.selectPage(eq(9L), any(), any(), eq(true), eq(PricingStatus.READY.name()), eq(NOW), anyLong(), anyInt()))
                .thenReturn(List.of(ready));
        when(readMapper.countPage(eq(9L), any(), any(), eq(true), eq(PricingStatus.READY.name()), eq(NOW)))
                .thenReturn(1L);

        assertThat(service.pageCatalog(pageRequest(true)).getList())
                .extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.AVAILABLE.name());
        verify(readMapper).selectPage(eq(9L), any(), any(), eq(true), eq(PricingStatus.READY.name()), eq(NOW), anyLong(), anyInt());
        verify(readMapper).countPage(eq(9L), any(), any(), eq(true), eq(PricingStatus.READY.name()), eq(NOW));
    }

    @Test
    void automaticRowWithMissingOrExpiredDeadlineReturnsUnknown() {
        BuyerCatalogReadRow expired = autoRow(LocalDateTime.of(2026, 9, 16, 11, 59, 59));
        BuyerCatalogReadRow missing = autoRow(null);
        when(readMapper.selectPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(NOW), anyLong(), anyInt()))
                .thenReturn(List.of(expired, missing));
        when(readMapper.countPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(NOW)))
                .thenReturn(2L);

        assertThat(service.pageCatalog(pageRequest(false)).getList())
                .extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNKNOWN.name(), ListingAvailability.UNKNOWN.name());
    }

    @Test
    void automaticRowWithStalePriceSourceReturnsUnknown() {
        BuyerCatalogReadRow staleSource = autoRow(NOW.plusSeconds(10));
        staleSource.setBuyerPricingCurrentVersionId(99L);
        BuyerCatalogReadRow fresh = autoRow(NOW.plusSeconds(10));
        when(readMapper.selectPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(NOW), anyLong(), anyInt()))
                .thenReturn(List.of(staleSource, fresh));
        when(readMapper.countPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(NOW)))
                .thenReturn(2L);

        assertThat(service.pageCatalog(pageRequest(false)).getList())
                .extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNKNOWN.name(), ListingAvailability.AVAILABLE.name());
    }

    @Test
    void manualRowKeepsLegacyBehaviorEvenWithMissingDeadline() {
        BuyerCatalogReadRow manual = autoRow(null);
        manual.setManagementMode(BuyerListingManagementMode.MANUAL.name());
        when(readMapper.selectPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(NOW), anyLong(), anyInt()))
                .thenReturn(List.of(manual));
        when(readMapper.countPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(NOW)))
                .thenReturn(1L);

        assertThat(service.pageCatalog(pageRequest(false)).getList())
                .extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.AVAILABLE.name());
    }

    @Test
    void autoUnreadyPricingIsUnknownWhileManualContractIsUnchanged() {
        BuyerCatalogReadRow auto = autoRow(NOW.plusSeconds(10));
        auto.setPricingStatus(PricingStatus.UNPRICED.name());
        BuyerCatalogReadRow manual = autoRow(NOW.plusSeconds(10));
        manual.setManagementMode(BuyerListingManagementMode.MANUAL.name());
        manual.setBasisJson("[]"); manual.setPricingStatus(PricingStatus.UNPRICED.name());
        when(readMapper.selectPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(NOW), anyLong(), anyInt()))
                .thenReturn(List.of(auto, manual));
        when(readMapper.countPage(eq(9L), any(), any(), eq(false), eq(PricingStatus.READY.name()), eq(NOW)))
                .thenReturn(2L);
        assertThat(service.pageCatalog(pageRequest(false)).getList())
                .extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus).containsExactly("UNKNOWN", "UNAVAILABLE");
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
        row.setUpdatedAt(NOW);
        return row;
    }

    /** AUTO行：来源与当前定价版本一致(7L)、期限内有效、定价READY；仅期限可传参制造过期/缺失场景。 */
    private static BuyerCatalogReadRow autoRow(LocalDateTime validUntil) {
        BuyerCatalogReadRow row = row(ListingAvailability.AVAILABLE.getCode(), PricingStatus.READY.name());
        row.setListingId(4L);
        row.setManagementMode(BuyerListingManagementMode.AUTO_SELECTION.name());
        row.setBasisJson("{\"listingId\":4,\"sourceBuyerPricingVersionId\":7}");
        row.setCommercialValidFrom(NOW.minusSeconds(1));
        row.setSourceBuyerPricingVersionId(7L);
        row.setBuyerPricingCurrentVersionId(7L);
        row.setEvaluatedAt(NOW.minusSeconds(1));
        row.setValidUntil(validUntil);
        return row;
    }
}
