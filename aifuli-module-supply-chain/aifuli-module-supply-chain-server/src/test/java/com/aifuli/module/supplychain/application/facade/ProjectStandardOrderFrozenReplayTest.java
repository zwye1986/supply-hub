package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderCreateRespDTO;
import com.aifuli.module.supplychain.interfaceapi.open.project.dto.ProjectStandardOrderCreateReqDTO;
import com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader;
import com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader.FrozenAcceptanceIdentity;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyAcceptanceCoordinator;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver.ProjectSnapshot;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver.BuyerSnapshot;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 冻结回执身份优先：Facade 在当前 Schema 解析前读冻结身份，有可信格式2绑定用冻结 SKU/Schema 算指纹，
 * 不重新解析当前目录；无冻结身份才走当前目录解析。设计 §8「冻结事实回放与兼容边界」。
 */
class ProjectStandardOrderFrozenReplayTest {

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final BuyerResolver buyerResolver = mock(BuyerResolver.class);
    private final BuyerProjectAccessResolver projectAccessResolver = mock(BuyerProjectAccessResolver.class);
    private final FulfillmentTargetSchemaRegistry schemaRegistry = mock(FulfillmentTargetSchemaRegistry.class);
    private final EnvelopeCryptoPort envelopeCrypto = mock(EnvelopeCryptoPort.class);
    private final HmacFingerprintPort hmacFingerprint = mock(HmacFingerprintPort.class);
    private final FulfillmentAcceptanceLifecycle lifecycle = mock(FulfillmentAcceptanceLifecycle.class);
    private final FrozenAcceptanceIdentityReader frozenIdentityReader = mock(FrozenAcceptanceIdentityReader.class);
    private final com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRequestCoordinator requestCoordinator =
            mock(com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRequestCoordinator.class);
    private final RechargeFrequencyAcceptanceCoordinator acceptanceCoordinator =
            mock(RechargeFrequencyAcceptanceCoordinator.class);
    private final StandardPricingQuoteService pricingQuoteService = mock(StandardPricingQuoteService.class);
    private final BusinessNoGenerator businessNoGenerator = mock(BusinessNoGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ProjectStandardOrderCommandFacade facade = new ProjectStandardOrderCommandFacade(
            clock, buyerResolver, projectAccessResolver, schemaRegistry, envelopeCrypto, hmacFingerprint,
            lifecycle, frozenIdentityReader, requestCoordinator, acceptanceCoordinator, pricingQuoteService,
            businessNoGenerator, objectMapper);

    @Test
    void frozenReplayDoesNotResolveCurrentCatalogOrValidateCurrentSchema() {
        when(projectAccessResolver.requireActive(5L, 9L)).thenReturn(new ProjectSnapshot(5L, "PRJ_A", "项目A", 9L));
        when(buyerResolver.requireActiveById(9L)).thenReturn(new BuyerSnapshot(9L, "BYR_A", "吾盛", "吾盛", 1));
        when(frozenIdentityReader.read(5L, "ORDER-1")).thenReturn(new FrozenAcceptanceIdentity(88L, "FROZEN_SCHEMA", 3));
        when(lifecycle.resolveTargetSchema(9L, "SKU_A")).thenReturn(
                new FulfillmentAcceptanceLifecycle.TargetSchemaSnapshot(88L, "CURRENT_SCHEMA", 1, true));
        when(businessNoGenerator.next(any())).thenReturn("REQ_1");
        when(clock.toInstant(any())).thenReturn(java.time.Instant.parse("2026-10-09T04:00:00Z"));
        when(hmacFingerprint.fingerprint(anyString(), any())).thenReturn(new byte[32]);
        FulfillmentAcceptanceLifecycle.AcceptanceSnapshot snapshot = new FulfillmentAcceptanceLifecycle.AcceptanceSnapshot(
                "RCP_1", "ORDER-1", (short) 10, 1L, "SO_1", "F_1", null, null, null,
                LocalDateTime.of(2026, 10, 9, 12, 0), true, (short) 10);
        when(lifecycle.claim(any())).thenReturn(new FulfillmentAcceptanceLifecycle.ClaimResult(1L, false, true));
        when(lifecycle.replay(1L)).thenReturn(snapshot);

        ProjectStandardOrderCreateRespDTO response = facade.create(
                new StandardProductContext(5L, 9L, "APP", 1, "STANDARD_ORDER_CREATE"), request());

        assertThat(response.getBuyerOrderNo()).isEqualTo("ORDER-1");
        assertThat(response.getIdempotentReplay()).isTrue();
        verify(lifecycle, never()).resolveTargetSchema(anyLong(), anyString());
        org.mockito.Mockito.verifyNoInteractions(schemaRegistry, envelopeCrypto, requestCoordinator, pricingQuoteService);
        ArgumentCaptor<FulfillmentAcceptanceLifecycle.PreparedAcceptance> prepared =
                ArgumentCaptor.forClass(FulfillmentAcceptanceLifecycle.PreparedAcceptance.class);
        verify(lifecycle).claim(prepared.capture());
        assertThat(prepared.getValue().targetSchemaCode()).isEqualTo("FROZEN_SCHEMA");
        assertThat(prepared.getValue().targetSchemaVersion()).isEqualTo(3);
        assertThat(prepared.getValue().targetValid()).isTrue();
    }

    @Test
    void withoutFrozenIdentityFallsBackToCurrentSchemaResolution() {
        when(projectAccessResolver.requireActive(5L, 9L)).thenReturn(new ProjectSnapshot(5L, "PRJ_A", "项目A", 9L));
        when(buyerResolver.requireActiveById(9L)).thenReturn(new BuyerSnapshot(9L, "BYR_A", "吾盛", "吾盛", 1));
        when(frozenIdentityReader.read(5L, "ORDER-1")).thenReturn(null);
        when(lifecycle.resolveTargetSchema(9L, "SKU_A")).thenReturn(
                new FulfillmentAcceptanceLifecycle.TargetSchemaSnapshot(88L, "CURRENT_SCHEMA", 1, true));
        when(businessNoGenerator.next(any())).thenReturn("REQ_1");
        when(clock.toInstant(any())).thenReturn(java.time.Instant.parse("2026-10-09T04:00:00Z"));
        when(hmacFingerprint.fingerprint(anyString(), any())).thenReturn(new byte[32]);
        FulfillmentAcceptanceLifecycle.AcceptanceSnapshot snapshot = new FulfillmentAcceptanceLifecycle.AcceptanceSnapshot(
                "RCP_1", "ORDER-1", (short) 10, 1L, "SO_1", "F_1", null, null, null,
                LocalDateTime.of(2026, 10, 9, 12, 0), true, (short) 10);
        when(lifecycle.claim(any())).thenReturn(new FulfillmentAcceptanceLifecycle.ClaimResult(1L, false, true));
        when(lifecycle.replay(1L)).thenReturn(snapshot);

        facade.create(new StandardProductContext(5L, 9L, "APP", 1, "STANDARD_ORDER_CREATE"), request());

        verify(lifecycle).resolveTargetSchema(eq(9L), eq("SKU_A"));
    }

    private static ProjectStandardOrderCreateReqDTO request() {
        ProjectStandardOrderCreateReqDTO request = new ProjectStandardOrderCreateReqDTO();
        request.setBuyerOrderNo("ORDER-1");
        request.setBuyerSkuCode("SKU_A");
        request.setQuantity(1);
        request.setTarget(java.util.Map.of("deliveryMode", "BUYER_ASSET_QUERY"));
        return request;
    }
}
