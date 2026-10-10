package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogResolver.CatalogIdentity;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogRefreshReason;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CurrentSupplyEligibility;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 首冻前准备目录/核验当前供给契约；见 docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#61-请求准备。 */
class AcceptanceCatalogPreparationServiceTest {
    private final com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock clock =
            mock(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class);
    private final BuyerSelectionCatalogLifecycle lifecycle = mock(BuyerSelectionCatalogLifecycle.class);
    private final CurrentSupplyEligibilityReader supplyReader = mock(CurrentSupplyEligibilityReader.class);
    private final BuyerListingMapper listingMapper = mock(BuyerListingMapper.class);
    private final BuyerListingVersionMapper versionMapper = mock(BuyerListingVersionMapper.class);
    private AcceptanceCatalogPreparationService service;

    @BeforeEach
    void setUp() {
        when(clock.now()).thenAnswer(invocation -> LocalDateTime.now());
        service = new AcceptanceCatalogPreparationService(clock, lifecycle, supplyReader, listingMapper, versionMapper);
    }

    @Test
    void autoCatalogCallsRefreshListingAndPassesWhenConfirmed() {
        var identity = new CatalogIdentity(1L, "BPD_1", 21L, "BPD_1", 31L, "LST_AUTO", 4L,
                BuyerListingManagementMode.AUTO_SELECTION.name());
        var listing = BuyerListingDO.builder().id(31L).listingNo("LST_AUTO").status(BuyerListingStatus.PUBLISHED.getCode())
                .currentVersionId(41L).managementMode(BuyerListingManagementMode.AUTO_SELECTION.name()).build();
        when(listingMapper.selectById(31L)).thenReturn(listing);
        when(versionMapper.selectById(41L)).thenReturn(BuyerListingVersionDO.builder().id(41L).listingId(31L).versionNo(1)
                .validFrom(LocalDateTime.now().minusDays(1)).build());
        when(supplyReader.evaluate(1L, 11L, 4L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.AVAILABLE, "CONFIRMED", LocalDateTime.now(), null));
        when(lifecycle.refreshListing("LST_AUTO", null)).thenReturn(new BuyerSelectionCatalogLifecycle.RefreshResult(
                "LST_AUTO", BuyerListingStatus.PUBLISHED.name(), ListingAvailability.AVAILABLE.name(),
                CatalogRefreshReason.CONFIRMED.name(), 101L, LocalDateTime.now(), LocalDateTime.now().plusSeconds(30)));

        Optional<AcceptanceCatalogPreparationService.PreparationFailure> failure =
                service.prepare(identity, 11L, 1001L);

        assertThat(failure).isEmpty();
        verify(lifecycle).refreshListing("LST_AUTO", null);
        verify(supplyReader).evaluate(1L, 11L, 4L);
    }

    @Test
    void autoCatalogFailsWithSuspendedRejection() {
        var identity = new CatalogIdentity(1L, "BPD_1", 21L, "BPD_1", 31L, "LST_AUTO", 4L,
                BuyerListingManagementMode.AUTO_SELECTION.name());
        when(lifecycle.refreshListing("LST_AUTO", null)).thenReturn(new BuyerSelectionCatalogLifecycle.RefreshResult(
                "LST_AUTO", BuyerListingStatus.SUSPENDED.name(), ListingAvailability.UNAVAILABLE.name(),
                CatalogRefreshReason.SUSPENDED.name(), null, LocalDateTime.now(), null));

        var result = service.prepare(identity, 11L, 1001L);
        assertThat(result).as("人工暂停必须拒绝").isPresent();
        var failure = result.orElseThrow();

        assertThat(failure.receiptStatus()).isEqualTo(AcceptanceReceiptStatus.REJECTED_RETRYABLE);
        assertThat(failure.rejectionCode()).isEqualTo(AcceptanceRejectionCode.LISTING_VERSION_EXPIRED);
    }

    @Test
    void autoCatalogDeferredPriceFailsWithQuoteMissingSignal() {
        var identity = new CatalogIdentity(1L, "BPD_1", 21L, "BPD_1", 31L, "LST_AUTO", 4L,
                BuyerListingManagementMode.AUTO_SELECTION.name());
        when(lifecycle.refreshListing("LST_AUTO", null)).thenReturn(new BuyerSelectionCatalogLifecycle.RefreshResult(
                "LST_AUTO", BuyerListingStatus.DRAFT.name(), ListingAvailability.UNKNOWN.name(),
                CatalogRefreshReason.DEFERRED_PRICE.name(), null, LocalDateTime.now(), null));

        assertThatThrownBy(() -> service.prepare(identity, 11L, 1001L))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void autoCatalogUnknownSupplyFailsWithRetryableInventoryRejection() {
        var identity = new CatalogIdentity(1L, "BPD_1", 21L, "BPD_1", 31L, "LST_AUTO", 4L,
                BuyerListingManagementMode.AUTO_SELECTION.name());
        when(lifecycle.refreshListing("LST_AUTO", null)).thenReturn(new BuyerSelectionCatalogLifecycle.RefreshResult(
                "LST_AUTO", BuyerListingStatus.DRAFT.name(), ListingAvailability.UNKNOWN.name(),
                "NO_ROUTABLE_SUPPLIER", 101L, LocalDateTime.now(), null));

        var failure = service.prepare(identity, 11L, 1001L).orElseThrow();

        assertThat(failure.receiptStatus()).isEqualTo(AcceptanceReceiptStatus.REJECTED_RETRYABLE);
        assertThat(failure.rejectionCode()).isEqualTo(AcceptanceRejectionCode.INVENTORY_INSUFFICIENT);
        assertThat(failure.listingVersionId()).as("DRAFT无商业版本，不能写定价版本101").isNull();
    }

    @Test
    void manualCatalogVerifiesCurrentProjectSupplyWithoutCallingLifecycleRefresh() {
        var identity = new CatalogIdentity(1L, "MANUAL_1", null, null, 32L, "LST_MANUAL", 4L,
                BuyerListingManagementMode.MANUAL.name());
        var listing = BuyerListingDO.builder().id(32L).listingNo("LST_MANUAL").status(BuyerListingStatus.PUBLISHED.getCode())
                .currentVersionId(51L).managementMode(BuyerListingManagementMode.MANUAL.name()).build();
        when(listingMapper.selectById(32L)).thenReturn(listing);
        when(versionMapper.selectById(51L)).thenReturn(BuyerListingVersionDO.builder().id(51L).listingId(32L).versionNo(2)
                .validFrom(LocalDateTime.now().minusDays(1)).build());
        when(supplyReader.evaluate(1L, 11L, 4L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.AVAILABLE, CatalogRefreshReason.CONFIRMED.name(), LocalDateTime.now(), null));

        Optional<AcceptanceCatalogPreparationService.PreparationFailure> failure =
                service.prepare(identity, 11L, 1001L);

        assertThat(failure).isEmpty();
        verify(supplyReader).evaluate(1L, 11L, 4L);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void autoBuyerAggregateCannotAuthorizeUnknownRequestProject() {
        var identity = new CatalogIdentity(1L, "BPD_1", 21L, "BPD_1", 31L, "LST_AUTO", 4L, "AUTO_SELECTION");
        when(lifecycle.refreshListing("LST_AUTO", null)).thenReturn(new BuyerSelectionCatalogLifecycle.RefreshResult(
                "LST_AUTO", "PUBLISHED", "AVAILABLE", "CONFIRMED", 101L, LocalDateTime.now(), null));
        when(listingMapper.selectById(31L)).thenReturn(BuyerListingDO.builder().id(31L)
                .status(BuyerListingStatus.PUBLISHED.getCode()).currentVersionId(41L).build());
        when(versionMapper.selectById(41L)).thenReturn(BuyerListingVersionDO.builder().id(41L).listingId(31L)
                .validFrom(LocalDateTime.now().minusDays(1)).build());
        when(supplyReader.evaluate(1L, 11L, 4L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.UNKNOWN, "SELECTED_SUPPLY_UNKNOWN", LocalDateTime.now(), null));
        var result = service.prepare(identity, 11L, 1001L);
        assertThat(result).as("真实请求项目UNKNOWN必须在首冻前拒绝").isPresent();
        var failure = result.orElseThrow();
        assertThat(failure.listingVersionId()).isEqualTo(41L);
        assertThat(failure.rejectionCode()).isEqualTo(AcceptanceRejectionCode.INVENTORY_INSUFFICIENT);
    }

    @Test
    void autoUnavailablePublishedUsesCommercialVersionNotPricingVersion() {
        var identity = new CatalogIdentity(1L, "BPD_1", 21L, "BPD_1", 31L, "LST_AUTO", 4L, "AUTO_SELECTION");
        when(lifecycle.refreshListing("LST_AUTO", null)).thenReturn(new BuyerSelectionCatalogLifecycle.RefreshResult(
                "LST_AUTO", "PUBLISHED", "UNAVAILABLE", "SUPPLIER_NOT_ACTIVE", 101L, LocalDateTime.now(), null));
        when(listingMapper.selectById(31L)).thenReturn(BuyerListingDO.builder().id(31L)
                .status(BuyerListingStatus.PUBLISHED.getCode()).currentVersionId(41L).build());
        when(versionMapper.selectById(41L)).thenReturn(BuyerListingVersionDO.builder().id(41L).listingId(31L)
                .validFrom(LocalDateTime.now().minusDays(1)).build());
        assertThat(service.prepare(identity, 11L, 1001L).orElseThrow().listingVersionId()).isEqualTo(41L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void manualInvalidCommercialWindowRejectsBeforeSupply(boolean future) {
        var now = LocalDateTime.now();
        var identity = new CatalogIdentity(1L, "MANUAL_1", null, null, 32L, "LST_MANUAL", 4L, "MANUAL");
        when(listingMapper.selectById(32L)).thenReturn(BuyerListingDO.builder().id(32L)
                .status(BuyerListingStatus.PUBLISHED.getCode()).currentVersionId(51L).build());
        when(versionMapper.selectById(51L)).thenReturn(BuyerListingVersionDO.builder().id(51L).listingId(32L)
                .validFrom(future ? now.plusDays(1) : now.minusDays(2))
                .validTo(future ? now.plusDays(2) : now.minusDays(1)).build());
        when(supplyReader.evaluate(1L, 11L, 4L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.AVAILABLE, "CONFIRMED", now, null));
        var result = service.prepare(identity, 11L, 1001L);
        assertThat(result).as("无效商业有效期必须在首冻前拒绝").isPresent();
        assertThat(result.orElseThrow().rejectionCode())
                .isEqualTo(AcceptanceRejectionCode.LISTING_VERSION_EXPIRED);
        verifyNoInteractions(supplyReader);
    }

    @Test
    void manualCatalogWithoutAvailableVersionFailsWithExpiredRejection() {
        var identity = new CatalogIdentity(1L, "MANUAL_1", null, null, 32L, "LST_MANUAL", 4L,
                BuyerListingManagementMode.MANUAL.name());
        var listing = BuyerListingDO.builder().id(32L).listingNo("LST_MANUAL").status(BuyerListingStatus.DRAFT.getCode())
                .managementMode(BuyerListingManagementMode.MANUAL.name()).build();
        when(listingMapper.selectById(32L)).thenReturn(listing);

        var failure = service.prepare(identity, 11L, 1001L).orElseThrow();

        assertThat(failure.receiptStatus()).isEqualTo(AcceptanceReceiptStatus.REJECTED_RETRYABLE);
        assertThat(failure.rejectionCode()).isEqualTo(AcceptanceRejectionCode.LISTING_VERSION_EXPIRED);
        verifyNoInteractions(supplyReader, lifecycle);
    }
}
