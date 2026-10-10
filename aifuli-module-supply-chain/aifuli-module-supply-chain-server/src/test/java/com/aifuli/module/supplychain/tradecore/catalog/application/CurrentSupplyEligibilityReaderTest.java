package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;
import com.aifuli.module.supplychain.routing.domain.RoutingDecision;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.RoutingConfigurationMissingException;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CurrentSupplyEligibility;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 首选资格判定的纯测：不得把首选 UNKNOWN 改选为后备 CONFIRMED。见最小业务设计 §5。 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CurrentSupplyEligibilityReaderTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 12, 0);

    @Test
    void optionalUnknownDoesNotChooseLowerConfirmed() throws Exception {
        RoutingCandidate first = candidate("FIRST", true, "UNKNOWN", List.of());
        RoutingCandidate second = candidate("SECOND", true, "CONFIRMED", List.of());
        assertResult(new RoutingDecision(first, List.of(first, second), "SELECTED"), ListingAvailability.UNKNOWN);
    }

    @Test
    void selectedConfirmedIsAvailable() throws Exception {
        RoutingCandidate first = candidate("FIRST", true, "CONFIRMED", List.of());
        assertResult(new RoutingDecision(first, List.of(first), "SELECTED"), ListingAvailability.AVAILABLE);
    }

    @Test
    void requiredMissingIsUnknownRatherThanDefiniteUnavailable() throws Exception {
        RoutingCandidate first = candidate("FIRST", false, "UNAVAILABLE", List.of("INVENTORY_FACT_REQUIRED"));
        assertResult(new RoutingDecision(null, List.of(first), "NO_ELIGIBLE_CANDIDATE"), ListingAvailability.UNKNOWN);
    }

    @Test
    void stoppedSupplierIsDefiniteUnavailable() throws Exception {
        RoutingCandidate first = candidate("FIRST", false, "UNAVAILABLE", List.of("SUPPLIER_NOT_ACTIVE"));
        assertResult(new RoutingDecision(null, List.of(first), "NO_ELIGIBLE_CANDIDATE"), ListingAvailability.UNAVAILABLE);
    }

    /** 直接使用真实资格值对象，不以 mock 替代路由候选的业务分类。 */
    private void assertResult(RoutingDecision decision, ListingAvailability availability) throws Exception {
        var snapshot = new SupplierRoutingApplicationService.RoutingSnapshot(1L, 2L, 1, new byte[32], 3L, "RULE", decision);
        CurrentSupplyEligibility result = CurrentSupplyEligibility.fromRouting(snapshot, NOW);
        assertThat(result.availability()).isEqualTo(availability);
        assertThat(result.routingSnapshot()).isSameAs(snapshot);
    }

    private static RoutingCandidate candidate(String code, boolean eligible, String availability, List<String> reasons) {
        return new RoutingCandidate(0, 1L, code, code, 2L, 3L, 4L, 5L, 100L, "CNY",
                80, 80, eligible, availability, reasons, "NOT_CAPTURED", "NOT_CHECKED", "NOT_CHECKED", null);
    }
    @Mock SupplyChainDatabaseClock clock;
    @Mock BuyerMapper buyers;
    @Mock BuyerProjectMapper projects;
    @Mock PlatformSkuMapper skus;
    @Mock PlatformSpuMapper spus;
    @Mock SupplierRoutingApplicationService routing;
    @Mock ProjectListingMapper projectListings;
    @Mock com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper policies;
    @Mock com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper policyVersions;
    @Mock com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper offers;
    @Mock com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper offerVersions;
    @Mock com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver connectorConfig;
    @Mock com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry metadataRegistry;
    @Mock com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator schemas;
    @Mock com.aifuli.module.supplychain.border.fulfillment.domain.OperationExecutorPort executor;
    @org.mockito.Spy com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    @InjectMocks CurrentSupplyEligibilityReader reader;

    @Test
    void readerUsesIndependentReadOnlyRepeatableReadTransaction() throws Exception {
        Transactional tx = CurrentSupplyEligibilityReader.class
                .getMethod("evaluate", Long.class, Long.class, Long.class).getAnnotation(Transactional.class);
        assertThat(tx.transactionManager()).isEqualTo("supplyChainTransactionManager");
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(tx.readOnly()).isTrue();
    }

    @Test
    void missingConfigurationIsUnknownAndSystemErrorsPropagate() {
        activeSubject();
        when(routing.route(any())).thenThrow(new RoutingConfigurationMissingException(
                RoutingConfigurationMissingException.Reason.NO_ACTIVE_POLICY, "missing"));
        assertThat(reader.evaluate(1L, 3L, 2L).availability()).isEqualTo(ListingAvailability.UNKNOWN);
        doThrow(new IllegalStateException("corrupt")).when(routing).route(any());
        assertThatThrownBy(() -> reader.evaluate(1L, 3L, 2L)).isInstanceOf(IllegalStateException.class)
                .hasMessage("corrupt");
    }

    @Test
    void projectOwnershipMismatchFailsClosedBeforeRouting() {
        when(clock.now()).thenReturn(NOW);
        when(buyers.selectById(1L)).thenReturn(BuyerDO.builder().id(1L).status((short) 10).build());
        when(projects.selectById(3L)).thenReturn(BuyerProjectDO.builder().id(3L).buyerId(999L).status((short) 10).build());
        assertThatThrownBy(() -> reader.evaluate(1L, 3L, 2L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(routing);
    }

    private void activeSubject() {
        when(clock.now()).thenReturn(NOW);
        when(buyers.selectById(1L)).thenReturn(BuyerDO.builder().id(1L).status((short) 10).build());
        when(projects.selectById(3L)).thenReturn(BuyerProjectDO.builder().id(3L).buyerId(1L).status((short) 10).build());
        when(skus.selectById(2L)).thenReturn(PlatformSkuDO.builder().id(2L).spuId(4L).status((short) 10).fulfillmentType("DIRECT_RECHARGE").build());
        when(spus.selectById(4L)).thenReturn(PlatformSpuDO.builder().id(4L).status((short) 10).build());
    }

    @Test
    void buyerSelectionWithNoProjectsUsesActualDefaultRoute() {
        when(clock.now()).thenReturn(NOW);
        when(buyers.selectById(1L)).thenReturn(BuyerDO.builder().id(1L).status((short) 10).build());
        when(skus.selectById(2L)).thenReturn(PlatformSkuDO.builder().id(2L).spuId(4L).status((short) 10).fulfillmentType("DIRECT_RECHARGE").build());
        when(spus.selectById(4L)).thenReturn(PlatformSpuDO.builder().id(4L).status((short) 10).build());
        RoutingCandidate first = candidate("FIRST", true, "CONFIRMED", List.of());
        when(routing.route(any())).thenReturn(new SupplierRoutingApplicationService.RoutingSnapshot(
                1L, 2L, 1, new byte[32], 3L, "RULE", new RoutingDecision(first, List.of(first), "SELECTED")));
        executionSource(true);
        assertThat(reader.evaluateBuyerSelection(1L, 5L, 2L).confirmed()).isTrue();
        verify(routing).route(argThat(input -> input.projectId() == null));
    }

    @Test
    void structurallyConfirmedWithoutExecutorIsUnknownWithoutFallback() {
        confirmedRoute();
        executionSource(false);
        var result = reader.evaluate(1L, 3L, 2L);
        assertThat(result.availability()).isEqualTo(ListingAvailability.UNKNOWN);
        assertThat(result.reason()).isEqualTo("EXECUTOR_NOT_SUPPORTED");
        assertThat(result.routingSnapshot().decision().selectedCandidate().supplierCode()).isEqualTo("FIRST");
        verify(executor, never()).execute(any());
    }

    @Test
    void brokenPublishedExecutionConfigurationPropagates() {
        confirmedRoute();
        when(offerVersions.selectById(3L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO.builder()
                        .id(3L).connectorVersionId(4L).build());
        when(connectorConfig.resolve(4L)).thenThrow(new IllegalStateException("broken published config"));
        assertThatThrownBy(() -> reader.evaluate(1L, 3L, 2L)).isInstanceOf(IllegalStateException.class)
                .hasMessage("broken published config");
    }

    @Test
    void futurePolicyAndNonSelectedOfferBoundProjectionInSameView() {
        confirmedRoute();
        executionSource(true);
        when(policies.selectActiveByType(any())).thenReturn(List.of(
                com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO.builder().id(1L).build()));
        when(policyVersions.selectPublished(1L)).thenReturn(List.of(
                com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO.builder()
                        .id(2L).validFrom(NOW.minusDays(1)).validTo(NOW.plusSeconds(10)).build()));
        when(offers.selectPublishedBySupplierAndSku(1L, 2L)).thenReturn(List.of(
                com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO.builder()
                        .id(2L).currentVersionId(3L).build(),
                com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO.builder()
                        .id(22L).currentVersionId(23L).build()));
        when(offerVersions.selectById(23L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO.builder()
                        .id(23L).offerId(22L).validFrom(NOW.plusSeconds(2)).build());
        assertThat(reader.evaluate(1L, 3L, 2L).nextBoundaryAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void confirmedProjectDoesNotHideAnotherInvalidProjectBinding() {
        activeSubject();
        when(projectListings.selectAvailableByBuyerProductId(5L)).thenReturn(List.of(
                ProjectListingDO.builder().projectId(3L).buyerProductId(5L).build(),
                ProjectListingDO.builder().projectId(33L).buyerProductId(5L).build()));
        when(projects.selectById(33L)).thenReturn(BuyerProjectDO.builder().id(33L).buyerId(999L).status((short) 10).build());
        var first = candidate("FIRST", true, "CONFIRMED", List.of());
        // 后续坏归属必须在任何路由计算前失败，不能被第一个可用项目掩盖。
        lenient().when(routing.route(any())).thenReturn(new SupplierRoutingApplicationService.RoutingSnapshot(
                1L, 2L, 1, new byte[32], 3L, "RULE", new RoutingDecision(first, List.of(first), "SELECTED")));
        assertThatThrownBy(() -> reader.evaluateBuyerSelection(1L, 5L, 2L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(routing);
    }

    @Test
    void aggregationKeepsFirstConfirmedButEvaluatesAllActiveProjects() {
        confirmedRoute(); executionSource(true);
        when(projectListings.selectAvailableByBuyerProductId(5L)).thenReturn(List.of(
                ProjectListingDO.builder().projectId(3L).buyerProductId(5L).build(),
                ProjectListingDO.builder().projectId(33L).buyerProductId(5L).build(),
                ProjectListingDO.builder().projectId(43L).buyerProductId(5L).build()));
        when(projects.selectById(33L)).thenReturn(BuyerProjectDO.builder().id(33L).buyerId(1L).status((short) 10).build());
        when(projects.selectById(43L)).thenReturn(BuyerProjectDO.builder().id(43L).buyerId(1L).status((short) 20).build());
        when(routing.route(argThat(input -> input != null && Long.valueOf(33L).equals(input.projectId()))))
                .thenThrow(new RoutingConfigurationMissingException(RoutingConfigurationMissingException.Reason.NO_MATCHED_RULE, "missing"));
        var result = reader.evaluateBuyerSelection(1L, 5L, 2L);
        assertThat(result.confirmed()).isTrue();
        verify(routing).route(argThat(input -> Long.valueOf(3L).equals(input.projectId())));
        verify(routing).route(argThat(input -> Long.valueOf(33L).equals(input.projectId())));
        verify(routing, never()).route(argThat(input -> input.projectId() == null || Long.valueOf(43L).equals(input.projectId())));
    }

    @Test
    void publishedQueryProfileMissingRequiredCapabilityIsSystemFailure() {
        confirmedRoute(); executionSource(true);
        offerVersions.selectById(3L).setCompletionMode("QUERY");
        assertThatThrownBy(() -> reader.evaluate(1L, 3L, 2L)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capabilities");
        verify(executor, never()).supports(any());
    }

    @Test
    void publishedOfferSchemaRejectionIsNotUnknownOrExecutorFallback() {
        confirmedRoute(); executionSource(true);
        doThrow(new IllegalArgumentException("invalid closed schema")).when(schemas).validate(any(), any());
        assertThatThrownBy(() -> reader.evaluate(1L, 3L, 2L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid closed schema");
        verify(executor, never()).supports(any());
    }

    private void confirmedRoute() {
        activeSubject();
        var first = candidate("FIRST", true, "CONFIRMED", List.of());
        when(routing.route(any())).thenReturn(new SupplierRoutingApplicationService.RoutingSnapshot(
                1L, 2L, 1, new byte[32], 3L, "RULE", new RoutingDecision(first, List.of(first), "SELECTED")));
    }

    private void executionSource(boolean supported) {
        when(offerVersions.selectById(3L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO.builder()
                        .id(3L).offerId(2L).connectorVersionId(4L).primaryCapabilityCode("CREATE_RECHARGE")
                        .completionMode("SYNCHRONOUS").createIdempotencyMode("NONE").unknownResultPolicy("MANUAL")
                        .partialDeliveryMode("FORBIDDEN").resultMultiplicity("SINGLE")
                        .fulfillmentConfigSchemaVersion(1).fulfillmentConfigJson("{}")
                        .validFrom(NOW.minusDays(1)).build());
        when(connectorConfig.resolve(4L)).thenReturn(new
                com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration(
                "CON_TEST", 4L, 1, "TEST", "https://test.invalid", "merchant", 1L, null,
                java.util.Set.of(com.aifuli.module.supplychain.supplieradapter.metadata.Capability.CREATE_RECHARGE)));
        var metadata = mock(com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadata.class);
        when(metadataRegistry.require("TEST")).thenReturn(metadata);
        when(metadata.capabilities()).thenReturn(java.util.Set.of(
                com.aifuli.module.supplychain.supplieradapter.metadata.Capability.CREATE_RECHARGE));
        when(metadata.offerSchemaResource()).thenReturn("test-schema");
        when(executor.supports("TEST")).thenReturn(supported);
    }
}
