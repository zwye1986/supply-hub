package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService.RoutingSnapshot;
import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;
import com.aifuli.module.supplychain.routing.domain.RoutingDecision;
import com.aifuli.module.supplychain.routing.domain.RoutingDecisionStatus;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogResolver;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogResolver.CatalogIdentity;
import com.aifuli.module.supplychain.tradecore.catalog.application.CurrentSupplyEligibilityReader;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductSellabilityService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CurrentSupplyEligibility;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService.FrozenQuote;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 最终HTTP当前供给准入与复用RoutingSnapshot；见 docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#62-最终事务。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FulfillmentAcceptanceDirectProjectOrderAdmissionTest {
    @Mock private SupplyChainDatabaseClock clock;
    @Mock private FulfillmentAcceptanceReceiptMapper receiptMapper;
    @Mock private SupplyChainOrderMapper orderMapper;
    @Mock private OrderStatusLogMapper statusLogMapper;
    @Mock private FulfillmentRequestMapper requestMapper;
    @Mock private SupplierRoutingRoundMapper routingRoundMapper;
    @Mock private SupplierRoutingRecordMapper routingRecordMapper;
    @Mock private BOrderMapper bOrderMapper;
    @Mock private FulfillmentOperationMapper operationMapper;
    @Mock private BuyerListingMapper listingMapper;
    @Mock private BuyerAcceptanceCatalogResolver catalogResolver;
    @Mock private BuyerListingVersionMapper listingVersionMapper;
    @Mock private BuyerListingAvailabilityMapper listingAvailabilityMapper;
    @Mock private PlatformSkuMapper skuMapper;
    @Mock private ProductSellabilityService productSellabilityService;
    @Mock private SkuPurchaseLimitService purchaseLimitService;
    @Mock private StandardPricingQuoteService pricingQuoteService;
    @Mock private SupplierRoutingApplicationService routingService;
    @Mock private CurrentSupplyEligibilityReader supplyEligibilityReader;
    @Mock private com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogLifecycle catalogLifecycle;
    @Mock private FundReservationService fundReservationService;
    @Mock private RechargeFrequencyLifecycle rechargeFrequencyLifecycle;
    @Mock private OutboxEventRepository outboxRepository;
    @Mock private BusinessNoGenerator businessNoGenerator;
    @Mock private ObjectMapper objectMapper;
    @Mock private OrderSummaryPolicyRegistry orderSummaryPolicyRegistry;
    @Mock private ObjectProvider<AcceptanceFailureInjector> failureInjector;

    @InjectMocks
    private FulfillmentAcceptanceLifecycle lifecycle;

    @Test
    void directHttpAcceptsPublishedManualEvenIfSharedAvailabilityIsUnknown() {
        var command = command(BuyerListingManagementMode.MANUAL.name(), null);
        stubCommonFixtures(BuyerListingManagementMode.MANUAL.name(), ListingAvailability.UNKNOWN);
        RoutingSnapshot snapshot = confirmedSnapshot();
        when(supplyEligibilityReader.evaluate(1L, 11L, 4L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.AVAILABLE, "CONFIRMED", LocalDateTime.of(2026, 10, 9, 12, 0), snapshot));

        AcceptanceSnapshot result = lifecycle.acceptDirectProjectOrder(101L, command);

        assertThat(result.orderNo()).isEqualTo("SC_DIRECT");
        verify(supplyEligibilityReader).evaluate(1L, 11L, 4L);
        verify(routingService, never()).route(any());
    }

    @Test
    void directHttpRejectsWhenCurrentReaderIsNotConfirmedWithoutCallingLegacyRoute() {
        var command = command(BuyerListingManagementMode.MANUAL.name(), null);
        stubCommonFixtures(BuyerListingManagementMode.MANUAL.name(), ListingAvailability.AVAILABLE);
        when(supplyEligibilityReader.evaluate(1L, 11L, 4L)).thenReturn(new CurrentSupplyEligibility(
                ListingAvailability.UNKNOWN, "NO_ROUTABLE_SUPPLIER", LocalDateTime.of(2026, 10, 9, 12, 0), null));

        assertThatThrownBy(() -> lifecycle.acceptDirectProjectOrder(101L, command))
                .isInstanceOf(BusinessRejection.class)
                .satisfies(error -> {
                    var rejection = (BusinessRejection) error;
                    assertThat(rejection.rejectionCode()).isEqualTo(AcceptanceRejectionCode.NO_ROUTABLE_SUPPLIER);
                });
        verify(routingService, never()).route(any());
        verifyNoInteractions(fundReservationService, orderMapper);
    }

    @Test
    void rpcRouteStillRejectsWhenSharedAvailabilityIsUnknown() {
        var command = command(BuyerListingManagementMode.MANUAL.name(), null);
        stubCommonFixtures(BuyerListingManagementMode.MANUAL.name(), ListingAvailability.UNKNOWN);

        assertThatThrownBy(() -> lifecycle.accept(101L, command))
                .isInstanceOf(BusinessRejection.class)
                .satisfies(error -> {
                    var rejection = (BusinessRejection) error;
                    assertThat(rejection.rejectionCode()).isEqualTo(AcceptanceRejectionCode.INVENTORY_INSUFFICIENT);
                });
        verifyNoInteractions(supplyEligibilityReader, routingService);
        verifyNoInteractions(catalogLifecycle);
    }

    @Test
    void autoCurrentPriceFailureStopsBeforeSupplyOrderAndFunds() {
        var command = command(BuyerListingManagementMode.AUTO_SELECTION.name(), 21L);
        stubCommonFixtures(BuyerListingManagementMode.AUTO_SELECTION.name(), ListingAvailability.AVAILABLE);
        doThrow(new com.aifuli.module.supplychain.tradecore.catalog.pricing.ProductNotSaleableException("current AUTO pricing is no longer READY"))
                .when(catalogLifecycle).requireCurrentAutoAdmission(any(), any());
        assertThatThrownBy(() -> lifecycle.acceptDirectProjectOrder(101L, command))
                .isInstanceOf(BusinessRejection.class).satisfies(error ->
                        assertThat(((BusinessRejection) error).rejectionCode())
                                .isEqualTo(AcceptanceRejectionCode.LISTING_VERSION_EXPIRED));
        var sequence = inOrder(pricingQuoteService, catalogLifecycle);
        sequence.verify(pricingQuoteService).requireForProject(any());
        sequence.verify(pricingQuoteService).requireCurrentProjectAdmission(any(), any());
        sequence.verify(catalogLifecycle).requireCurrentAutoAdmission(any(), any());
        verifyNoInteractions(supplyEligibilityReader, orderMapper, fundReservationService);
    }

    private void stubCommonFixtures(String mode, ListingAvailability availability) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);
        when(clock.now()).thenReturn(now);
        when(receiptMapper.selectByIdForUpdate(101L)).thenReturn(FulfillmentAcceptanceReceiptDO.builder()
                .id(101L).receiptNo("RCP_101").status(AcceptanceReceiptStatus.PROCESSING.getCode())
                .requestFingerprint(new byte[]{1, 2, 3}).version(0L).build());
        BuyerListingDO listing = BuyerListingDO.builder().id(31L).listingNo("LST_1").buyerId(1L).platformSkuId(4L)
                .status(BuyerListingStatus.PUBLISHED.getCode()).currentVersionId(41L)
                .managementMode(mode).buyerProductId(mode.equals("AUTO_SELECTION") ? 21L : null).build();
        when(catalogResolver.lockResolvedIdentity(any())).thenReturn(listing);
        PlatformSkuDO sku = PlatformSkuDO.builder().id(4L).skuCode("SKU_4").skuName("测试商品")
                .targetSchemaCode("CARD_TARGET").targetSchemaVersion(1)
                .fulfillmentType(com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType.CARD_CODE_SECRET.name()).build();
        when(skuMapper.selectByIdForUpdate(4L)).thenReturn(sku);
        when(listingVersionMapper.selectById(41L)).thenReturn(BuyerListingVersionDO.builder()
                .id(41L).listingId(31L).versionNo(1).validFrom(now.minusDays(1)).validTo(now.plusDays(1)).build());
        when(listingAvailabilityMapper.selectByListingId(31L)).thenReturn(BuyerListingAvailabilityDO.builder()
                .listingId(31L).availability(availability.getCode()).build());
        when(productSellabilityService.requireSellable(sku)).thenReturn(new ProductSellabilityService.SellableProduct(sku, null));
        when(pricingQuoteService.requireForProject(any())).thenReturn(new FrozenQuote(51L, 61L, 4L,
                9800L, 10000L, 9800L, 0L, 9800L, null, false, null));
        when(purchaseLimitService.validateLockedSku(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(SkuPurchaseLimitService.Violation.NONE);
        when(businessNoGenerator.next(anyString())).thenReturn("SC_DIRECT", "RR_1", "BO_1", "BILL_1", "OP_1");
        when(receiptMapper.markAccepted(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(receiptMapper.selectById(101L)).thenReturn(FulfillmentAcceptanceReceiptDO.builder()
                .id(101L).receiptNo("RCP_101").buyerOrderNo("ORDER_DIRECT").status(AcceptanceReceiptStatus.ACCEPTED.getCode())
                .version(1L).resultOrderNo("SC_DIRECT").acceptedAt(now).build());
        when(orderMapper.insert(any(com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO.class))).thenAnswer(i -> {
            i.<com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO>getArgument(0).setId(201L);
            return 1;
        });
        when(requestMapper.insert(any(com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO.class))).thenAnswer(i -> {
            i.<com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO>getArgument(0).setId(301L);
            return 1;
        });
        when(routingRoundMapper.insert(any(com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRoundDO.class))).thenAnswer(i -> {
            i.<com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRoundDO>getArgument(0).setId(401L);
            return 1;
        });
        when(routingRecordMapper.insert(any(com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRecordDO.class))).thenAnswer(i -> {
            i.<com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SupplierRoutingRecordDO>getArgument(0).setId(501L);
            return 1;
        });
        when(bOrderMapper.insert(any(com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO.class))).thenAnswer(i -> {
            i.<com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO>getArgument(0).setId(601L);
            return 1;
        });
        when(orderMapper.selectById(any())).thenReturn(null);
        when(orderMapper.attachDetail(any(), any(), anyLong(), any())).thenReturn(1);
        when(requestMapper.activateBOrder(any(), any(), any())).thenReturn(1);
        when(routingRecordMapper.attachBOrder(any(), any())).thenReturn(1);
        when(orderMapper.advanceDetailVersion(any(), anyLong(), anyLong(), any())).thenReturn(1);
        when(fundReservationService.freeze(any())).thenReturn(new FundReservationService.ReservationSnapshot(
                "RES_1", 1L, "ORDER", "SC_DIRECT", 9800L, 9800L, 0L, 0L, (short) 1, 0L));
        OrderSummaryPolicy mockPolicy = mock(OrderSummaryPolicy.class);
        when(mockPolicy.decide(any())).thenReturn(new OrderSummaryDecision((short) 1, (short) 0, "ACCEPTED"));
        when(orderSummaryPolicyRegistry.require(any())).thenReturn(mockPolicy);
    }

    private PreparedAcceptance command(String mode, Long buyerProductId) {
        var identity = new CatalogIdentity(1L, "CODE_1", buyerProductId, "CODE_1", 31L, "LST_1", 4L, mode);
        return new PreparedAcceptance(11L, "PRJ_1", "项目1", 1L, "BYR_1", "买家1", "ORDER_DIRECT",
                null, "CARD", "CODE_1", 1L, "CARD_TARGET", 1, null, new byte[]{9}, "k1", true,
                new byte[]{1, 2, 3}, new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, null, "REQ_1", identity);
    }

    private RoutingSnapshot confirmedSnapshot() {
        var candidate = new RoutingCandidate(0, 1001L, "SUPP_1", "供应商1", 2001L, 2011L, 3001L, 4001L,
                9000L, "CNY", 100, 100, true, "CONFIRMED", List.of(),
                "CAPTURED", "CHECKED", "CHECKED", LocalDateTime.now());
        var decision = new RoutingDecision(candidate, List.of(candidate), "CONFIRMED");
        return new RoutingSnapshot(1L, 11L, 1, new byte[]{7, 8, 9}, 101L, "RULE_101", decision);
    }
}
