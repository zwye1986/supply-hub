package com.aifuli.module.supplychain.application.facade;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.fulfillment.dto.AcceptFulfillmentReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentTargetDTO;
import com.aifuli.module.supplychain.border.fulfillment.application.*;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.*;
import com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader.FrozenAcceptanceIdentity;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.interfaceapi.open.project.dto.ProjectStandardOrderCreateReqDTO;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.catalog.application.AcceptanceCatalogPreparationService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogResolver.CatalogIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 双Facade冻结指纹/回放边界；见 docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#61-请求准备。 */
class FrozenAcceptanceReplayFacadeTest {
    private static final Map<String, String> TARGET = Map.of("deliveryMode", "RETURN");
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final BuyerResolver buyers = mock(BuyerResolver.class);
    private final BuyerProjectAccessResolver projects = mock(BuyerProjectAccessResolver.class);
    private final FrozenAcceptanceIdentityReader frozen = mock(FrozenAcceptanceIdentityReader.class);
    private final FulfillmentTargetSchemaRegistry schemas = mock(FulfillmentTargetSchemaRegistry.class);
    private final EnvelopeCryptoPort crypto = mock(EnvelopeCryptoPort.class);
    private final HmacFingerprintPort hmac = mock(HmacFingerprintPort.class);
    private final FulfillmentAcceptanceLifecycle lifecycle = mock(FulfillmentAcceptanceLifecycle.class);
    private final RechargeFrequencyAcceptanceCoordinator coordinator = mock(RechargeFrequencyAcceptanceCoordinator.class);
    private final AcceptanceCatalogPreparationService catalogPreparationService = mock(AcceptanceCatalogPreparationService.class);
    private final StandardPricingQuoteService quotes = mock(StandardPricingQuoteService.class);
    private final BusinessNoGenerator numbers = mock(BusinessNoGenerator.class);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        var buyer = new BuyerResolver.BuyerSnapshot(1L, "BYR_FROZEN", "冻结买家", "冻结买家", 1);
        var project = new BuyerProjectAccessResolver.ProjectSnapshot(11L, "PRJ_FROZEN", "冻结项目", 1L);
        when(clock.now()).thenReturn(LocalDateTime.of(2026, 10, 9, 12, 0));
        when(buyers.requireActiveById(1L)).thenReturn(buyer);
        when(buyers.resolve(eq("SOURCE"), eq("TENANT"), any())).thenReturn(buyer);
        when(projects.requireActive(11L, 1L)).thenReturn(project);
        when(projects.requireActiveByCode("PRJ_FROZEN", 1L)).thenReturn(project);
        when(frozen.find(1L, 11L, "ORDER_FROZEN"))
                .thenReturn(Optional.of(new FrozenAcceptanceIdentity(4L, "CARD_TARGET", 1, true)));
        when(hmac.fingerprint(anyString(), any())).thenAnswer(invocation ->
                MessageDigest.getInstance("SHA-256").digest(invocation.getArgument(1, byte[].class)));
        when(numbers.next(anyString())).thenReturn("REQ_FROZEN");
        when(lifecycle.claim(any())).thenReturn(new ClaimResult(101L, false, true));
        when(lifecycle.replay(101L)).thenReturn(new AcceptanceSnapshot("RCP_FROZEN", "ORDER_FROZEN",
                AcceptanceReceiptStatus.ACCEPTED.getCode(), 1L, "SC_FROZEN", "FR_FROZEN",
                null, null, null, null, true, null));
        // 现行目录已换SKU/Schema；重放必须完全忽略它。
        when(lifecycle.resolveTargetSchema(1L, "BPD_FROZEN"))
                .thenReturn(new TargetSchemaSnapshot(9L, "NEW_SCHEMA", 7, true));
        when(crypto.encrypt(any(), any())).thenReturn(new EnvelopeCryptoPort.EncryptedPayload(new byte[32], "key"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptedReplayFingerprintsFrozenSkuSchemaAndSkipsCurrentPreparation(boolean http) throws Exception {
        invoke(http, TARGET);
        var prepared = ArgumentCaptor.forClass(PreparedAcceptance.class);
        verify(lifecycle).claim(prepared.capture());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(new TreeMap<>(Map.of(
                "schemaCode", "CARD_TARGET", "schemaVersion", 1, "values", new TreeMap<>(TARGET)))));
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(StandardAcceptanceFingerprintPolicy.canonicalRequest(
                new StandardAcceptanceFingerprintPolicy.RequestIdentity(1L, "PRJ_FROZEN", "ORDER_FROZEN", "BPD_FROZEN",
                        4L, 1L, "CARD_TARGET", 1, HexFormat.of().formatHex(digest))));
        assertThat(prepared.getValue().requestFingerprint()).isEqualTo(expected);
        verify(lifecycle, never()).resolveTargetSchema(any(), anyString());
        verifyNoInteractions(schemas, crypto, quotes, coordinator);
        verify(lifecycle).replay(101L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changedTargetStillConflictsThroughClaimInsteadOfBlindReplay(boolean http) {
        when(lifecycle.claim(any())).thenThrow(new IdempotencyConflictException());
        assertThatThrownBy(() -> invoke(http, Map.of("deliveryMode", "OTHER"))).isInstanceOf(ServiceException.class);
        verify(lifecycle, never()).replay(anyLong());
        verify(lifecycle, never()).resolveTargetSchema(any(), anyString());
        verifyNoInteractions(quotes, coordinator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void untrustedHistoricalIdentityReturnsStableConflictWithoutCurrentFallback(boolean http) {
        when(frozen.find(1L, 11L, "ORDER_FROZEN")).thenThrow(new IdempotencyConflictException());
        assertThatThrownBy(() -> invoke(http, TARGET)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(lifecycle, schemas, crypto, quotes, coordinator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void inactiveProjectIsRejectedBeforeFrozenRead(boolean http) {
        when(projects.requireActive(11L, 1L)).thenThrow(new IllegalStateException("inactive"));
        when(projects.requireActiveByCode("PRJ_FROZEN", 1L)).thenThrow(new IllegalStateException("inactive"));
        assertThatThrownBy(() -> invoke(http, TARGET)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(frozen, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void processingContinuationKeepsFrozenSchemaAndBindsCurrentCatalogOnlyAfterClaim(boolean http) throws Exception {
        when(frozen.find(1L, 11L, "ORDER_FROZEN"))
                .thenReturn(Optional.of(new FrozenAcceptanceIdentity(4L, "CARD_TARGET", 1, false)));
        var identity = new CatalogIdentity(1L, "BPD_FROZEN", 21L, "BPD_FROZEN", 31L, "LST_FROZEN", 4L, "AUTO_SELECTION");
        when(lifecycle.resolveTargetSchema(1L, "BPD_FROZEN"))
                .thenReturn(new TargetSchemaSnapshot(4L, "NEW_SCHEMA", 7, true, identity));
        when(lifecycle.claim(any())).thenReturn(new ClaimResult(101L, true, false));
        var snapshot = new AcceptanceSnapshot("RCP_FROZEN", "ORDER_FROZEN",
                AcceptanceReceiptStatus.ACCEPTED.getCode(), 1L, "SC_FROZEN", "FR_FROZEN", null, null, null, null, false, null);
        when(coordinator.acceptDirectProjectOrder(eq(101L), any())).thenReturn(snapshot);
        when(coordinator.accept(eq(101L), any())).thenReturn(snapshot);
        invoke(http, TARGET);
        var order = inOrder(lifecycle, coordinator);
        order.verify(lifecycle).claim(any());
        order.verify(lifecycle).resolveTargetSchema(1L, "BPD_FROZEN");
        var prepared = ArgumentCaptor.forClass(PreparedAcceptance.class);
        if (http) { order.verify(coordinator).acceptDirectProjectOrder(eq(101L), prepared.capture()); }
        else { order.verify(coordinator).accept(eq(101L), prepared.capture()); }
        assertThat(prepared.getValue().targetSchemaCode()).isEqualTo("CARD_TARGET");
        assertThat(prepared.getValue().targetSchemaVersion()).isEqualTo(1);
        assertThat(prepared.getValue().catalogIdentity()).isEqualTo(identity);
        verify(schemas).validate(eq("CARD_TARGET"), eq(1), any());
    }

    @Test
    void httpPreparationFailureRejectsBeforePriceFreeze() throws Exception {
        when(frozen.find(1L, 11L, "ORDER_FROZEN"))
                .thenReturn(Optional.of(new FrozenAcceptanceIdentity(4L, "CARD_TARGET", 1, false)));
        var identity = new CatalogIdentity(1L, "BPD_FROZEN", 21L, "BPD_FROZEN", 31L, "LST_FROZEN", 4L, "AUTO_SELECTION");
        when(lifecycle.resolveTargetSchema(1L, "BPD_FROZEN"))
                .thenReturn(new TargetSchemaSnapshot(4L, "CARD_TARGET", 1, true, identity));
        when(lifecycle.claim(any())).thenReturn(new ClaimResult(101L, true, false));
        when(catalogPreparationService.prepare(eq(identity), eq(11L), eq(101L))).thenReturn(Optional.of(
                new AcceptanceCatalogPreparationService.PreparationFailure(101L, 41L,
                        AcceptanceReceiptStatus.REJECTED_RETRYABLE, AcceptanceRejectionCode.INVENTORY_INSUFFICIENT)));
        when(lifecycle.reject(any())).thenReturn(new AcceptanceSnapshot("RCP_FROZEN", "ORDER_FROZEN",
                AcceptanceReceiptStatus.REJECTED_RETRYABLE.getCode(), 1L, null, null,
                AcceptanceRejectionCode.INVENTORY_INSUFFICIENT.name(), "可兑现供给不足", null, null, false, null));

        var facade = facade(ProjectStandardOrderCommandFacade.class);
        var request = new ProjectStandardOrderCreateReqDTO();
        request.setBuyerOrderNo("ORDER_FROZEN"); request.setBuyerSkuCode("BPD_FROZEN");
        request.setQuantity(1); request.setTarget(TARGET);
        var response = facade.create(new StandardProductContext(11L, 1L, "APP_FROZEN", 1,
                ProjectAuthOperations.STANDARD_ORDER_CREATE), request);

        assertThat(response.getReceiptStatus()).isEqualTo(com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus.REJECTED_RETRYABLE);
        verify(quotes, never()).freezeForProject(any());
        verify(coordinator, never()).acceptDirectProjectOrder(anyLong(), any());
    }

    private void invoke(boolean http, Map<String, String> target) throws Exception {
        if (http) {
            var facade = facade(ProjectStandardOrderCommandFacade.class);
            var request = new ProjectStandardOrderCreateReqDTO();
            request.setBuyerOrderNo("ORDER_FROZEN"); request.setBuyerSkuCode("BPD_FROZEN");
            request.setQuantity(1); request.setTarget(target);
            facade.create(new StandardProductContext(11L, 1L, "APP_FROZEN", 1,
                    ProjectAuthOperations.STANDARD_ORDER_CREATE), request);
        } else {
            var request = new AcceptFulfillmentReqDTO();
            request.setSourceSystem("SOURCE"); request.setExternalTenantId("TENANT");
            request.setProjectCode("PRJ_FROZEN"); request.setBuyerOrderNo("ORDER_FROZEN");
            request.setBuyerSkuCode("BPD_FROZEN"); request.setQuantity(1L);
            var dto = new FulfillmentTargetDTO(); dto.setValues(target); request.setTarget(dto);
            facade(BuyerFulfillmentCommandFacade.class).acceptFulfillment(request);
        }
    }

    private <T> T facade(Class<T> type) throws Exception {
        // 通过实际构造器装配所有已有协作者，新增依赖也必须有明确fixture，缺读服务的红灯为行为断言。
        Object[] args = java.util.Arrays.stream(type.getConstructors()[0].getParameterTypes()).map(this::dependency).toArray();
        T facade = type.cast(type.getConstructors()[0].newInstance(args));
        return facade;
    }

    private Object dependency(Class<?> type) {
        for (Object dependency : new Object[]{clock, buyers, projects, schemas, crypto, hmac, lifecycle,
                coordinator, catalogPreparationService, quotes, numbers, json, frozen}) {
            if (type.isInstance(dependency)) { return dependency; }
        }
        throw new AssertionError("缺少显式Facade依赖fixture: " + type.getName());
    }
}
