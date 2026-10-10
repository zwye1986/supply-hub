package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerCatalogRefreshOutcome;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首冻前准备：AUTO刷新、MANUAL零写核商业发布、目录未就绪与供给明确不可用不proceed、
 * 缺价放行沿原PROCESSING契约、技术失败上抛。设计 §7.2。
 */
class BuyerSelectionCatalogRequestCoordinatorTest {

    private final BuyerSelectionCatalogRefreshService refresh = mock(BuyerSelectionCatalogRefreshService.class);
    private final BuyerListingMapper listings = mock(BuyerListingMapper.class);
    private final com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper versions =
            mock(com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper.class);
    private final com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper skus =
            mock(com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper.class);
    private final com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock clock =
            mock(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class);
    private final CurrentSupplyEligibilityReader reader = mock(CurrentSupplyEligibilityReader.class);
    private final BuyerSelectionCatalogRequestCoordinator coordinator =
            new BuyerSelectionCatalogRequestCoordinator(refresh, listings, versions, skus, clock, reader);

    @org.junit.jupiter.api.BeforeEach
    void configureConfirmedProject() {
        var now = LocalDateTime.of(2026, 10, 10, 12, 0);
        when(clock.now()).thenReturn(now);
        when(versions.selectById(3L)).thenReturn(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO
                .builder().id(3L).listingId(7L).validFrom(now.minusDays(1)).build());
        when(skus.selectById(88L)).thenReturn(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO
                .builder().id(88L).fulfillmentType("VOUCHER_CODE").build());
        when(reader.readProject(any())).thenReturn(new CurrentSupplyEligibilityReader.ProjectEligibility(
                5L, SupplyQualification.CONFIRMED, null, java.util.List.of(), now, "fixture", null));
    }

    private BuyerSelectionCatalogRequestCoordinator.Preparation prepare(BuyerAcceptanceCatalogResolver.CatalogIdentity identity) {
        return coordinator.prepare(identity, 5L, 1);
    }

    @Test
    void missingOrUnavailableIdentityProceedsAlongOriginalAdmission() {
        assertThat(prepare(null).proceed()).isTrue();
        assertThat(prepare(identity(7L, null)).proceed()).isTrue();
        verify(refresh, org.mockito.Mockito.never()).refresh(anyLong(), any(), any());
    }

    @Test
    void manualPublishedWithVersionProceedsWithoutAnyWrite() {
        BuyerListingDO listing = BuyerListingDO.builder().id(7L).status(BuyerListingStatus.PUBLISHED.getCode())
                .currentVersionId(3L).build();
        when(listings.selectById(7L)).thenReturn(listing);

        BuyerSelectionCatalogRequestCoordinator.Preparation preparation =
                prepare(identity(7L, BuyerListingManagementMode.MANUAL.name()));

        assertThat(preparation.proceed()).isTrue();
        assertThat(preparation.listingVersionId()).isEqualTo(3L);
        verify(refresh, org.mockito.Mockito.never()).refresh(anyLong(), any(), any());
    }

    @Test
    void manualDraftRejectsWithoutAnyWrite() {
        BuyerListingDO listing = BuyerListingDO.builder().id(7L).status(BuyerListingStatus.DRAFT.getCode()).build();
        when(listings.selectById(7L)).thenReturn(listing);
        assertThat(prepare(identity(7L, BuyerListingManagementMode.MANUAL.name())).proceed()).isFalse();
        verify(refresh, org.mockito.Mockito.never()).refresh(anyLong(), any(), any());
    }

    @Test
    void autoDraftOrSuspendedRejects() {
        BuyerListingDO listing = BuyerListingDO.builder().id(7L).status(BuyerListingStatus.DRAFT.getCode()).build();
        when(listings.selectById(7L)).thenReturn(listing);
        when(refresh.refresh(eq(7L), eq(BuyerSelectionCatalogRefreshService.Trigger.ORDER_PREPARE), isNull()))
                .thenReturn(result(BuyerCatalogRefreshOutcome.DEFERRED, BuyerListingStatus.DRAFT,
                        ListingAvailability.UNKNOWN, BuyerSelectionCatalogRefreshResult.Reason.PRICING_NOT_READY));
        assertThat(prepare(identity(7L, BuyerListingManagementMode.AUTO_SELECTION.name())).proceed()).isFalse();
    }

    @Test
    void autoPublishedWithUnavailableSupplyRejects() {
        BuyerListingDO listing = BuyerListingDO.builder().id(7L).status(BuyerListingStatus.PUBLISHED.getCode())
                .currentVersionId(3L).build();
        when(listings.selectById(7L)).thenReturn(listing);
        when(refresh.refresh(eq(7L), eq(BuyerSelectionCatalogRefreshService.Trigger.ORDER_PREPARE), isNull()))
                .thenReturn(result(BuyerCatalogRefreshOutcome.DEFERRED, BuyerListingStatus.PUBLISHED,
                        ListingAvailability.UNAVAILABLE,
                        BuyerSelectionCatalogRefreshResult.Reason.QUALIFICATION_UNAVAILABLE));
        assertThat(prepare(identity(7L, BuyerListingManagementMode.AUTO_SELECTION.name())).proceed()).isFalse();
    }

    @Test
    void autoPublishedWithMissingPriceProceedsAlongOriginalQuoteContract() {
        BuyerListingDO listing = BuyerListingDO.builder().id(7L).status(BuyerListingStatus.PUBLISHED.getCode())
                .currentVersionId(3L).build();
        when(listings.selectById(7L)).thenReturn(listing);
        when(refresh.refresh(eq(7L), eq(BuyerSelectionCatalogRefreshService.Trigger.ORDER_PREPARE), isNull()))
                .thenReturn(result(BuyerCatalogRefreshOutcome.DEFERRED, BuyerListingStatus.PUBLISHED,
                        ListingAvailability.UNAVAILABLE,
                        BuyerSelectionCatalogRefreshResult.Reason.PRICING_NOT_READY));
        BuyerSelectionCatalogRequestCoordinator.Preparation preparation =
                prepare(identity(7L, BuyerListingManagementMode.AUTO_SELECTION.name()));
        assertThat(preparation.proceed()).as("缺价放行由原首冻PROCESSING契约处理").isTrue();
        assertThat(preparation.listingVersionId()).isEqualTo(3L);
    }

    @Test
    void autoPublishedAndAvailableProceeds() {
        BuyerListingDO listing = BuyerListingDO.builder().id(7L).status(BuyerListingStatus.PUBLISHED.getCode())
                .currentVersionId(3L).build();
        when(listings.selectById(7L)).thenReturn(listing);
        when(refresh.refresh(eq(7L), eq(BuyerSelectionCatalogRefreshService.Trigger.ORDER_PREPARE), isNull()))
                .thenReturn(result(BuyerCatalogRefreshOutcome.UNCHANGED, BuyerListingStatus.PUBLISHED,
                        ListingAvailability.AVAILABLE, null));
        assertThat(prepare(identity(7L, BuyerListingManagementMode.AUTO_SELECTION.name())).proceed()).isTrue();
    }

    @Test
    void technicalRefreshFailurePropagatesWithoutFakingBusinessRejection() {
        BuyerListingDO listing = BuyerListingDO.builder().id(7L).status(BuyerListingStatus.PUBLISHED.getCode()).build();
        when(listings.selectById(7L)).thenReturn(listing);
        when(refresh.refresh(eq(7L), eq(BuyerSelectionCatalogRefreshService.Trigger.ORDER_PREPARE), isNull()))
                .thenThrow(new IllegalStateException("broken reference"));
        assertThatThrownBy(() -> prepare(identity(7L, BuyerListingManagementMode.AUTO_SELECTION.name())))
                .isInstanceOf(IllegalStateException.class);
    }

    private static BuyerAcceptanceCatalogResolver.CatalogIdentity identity(Long listingId, String managementMode) {
        return new BuyerAcceptanceCatalogResolver.CatalogIdentity(9L, "SKU_A", 21L, "BPD_A",
                listingId, "LST_A", 88L, managementMode);
    }

    private static BuyerSelectionCatalogRefreshResult result(BuyerCatalogRefreshOutcome outcome,
                                                             BuyerListingStatus publicationState,
                                                             ListingAvailability availability,
                                                             BuyerSelectionCatalogRefreshResult.Reason reason) {
        return new BuyerSelectionCatalogRefreshResult(outcome, publicationState, availability, reason,
                LocalDateTime.of(2026, 10, 9, 12, 0));
    }
}
