package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class SuggestedSalePriceCoordinatorTest {
    @Test void registersBothFutureBoundariesIncludingNonMinimumCandidatesAndPersistsEarliest() {
        var now = LocalDateTime.of(2026,10,19,12,0);
        var resolver = mock(SuggestedSalePriceResolver.class);
        var states = mock(SuggestedSalePriceStateService.class);
        var offers = mock(SupplierOfferMapper.class);
        var versions = mock(SupplierOfferVersionMapper.class);
        var clock = mock(SupplyChainDatabaseClock.class);
        PersistentTimer timer = mock(PersistentTimer.class);
        ObjectProvider<PersistentTimer> timers = mock(ObjectProvider.class);
        when(timers.getObject()).thenReturn(timer);
        when(clock.toInstant(any())).thenAnswer(call -> ((LocalDateTime) call.getArgument(0))
                .atZone(SupplyChainDatabaseClock.DATABASE_ZONE).toInstant());
        var sku = PlatformSkuDO.builder().id(1L).marketPrice(10000L).suggestedSalePrice(0L)
                .suggestedSalePriceRuleJson(new SuggestedSalePriceRule(SuggestedSalePriceRule.Mode.CUSTOM,
                        SuggestedSalePriceRule.Base.SUPPLY_PRICE, new BigDecimal("110"),
                        SuggestedSalePriceRule.Operator.ADD, BigDecimal.ONE, null, null)).build();
        var resolution = SuggestedSalePriceResolver.evaluate(sku, null, null, now);
        when(resolver.resolve(sku, now)).thenReturn(resolution);
        when(offers.selectList(any())).thenReturn(List.of(SupplierOfferDO.builder().id(2L)
                .platformSkuId(1L).currentVersionId(3L).build()));
        when(versions.selectBatchIds(anyCollection())).thenReturn(List.of(SupplierOfferVersionDO.builder()
                .id(3L).offerId(2L).validFrom(now.plusMinutes(1)).validTo(now.plusMinutes(2)).build()));
        var coordinator = new SuggestedSalePriceCoordinator(resolver, states, offers, versions,
                clock, new ObjectMapper(), timers);
        try {
            org.slf4j.MDC.put("traceId", "first-request");
            coordinator.refresh(sku, now);
            org.slf4j.MDC.put("traceId", "later-request");
            coordinator.refresh(sku, now);
        } finally { org.slf4j.MDC.clear(); }
        var payloads = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(timer, times(4)).scheduleOnce(anyString(), anyString(), payloads.capture(), any());
        assertThat(payloads.getAllValues().get(0)).isEqualTo(payloads.getAllValues().get(2));
        assertThat(payloads.getAllValues().get(0).has("originTraceId")).isFalse();
        verify(timer, times(4)).scheduleOnce(eq(SuggestedPriceBoundaryTimerHandler.TASK_TYPE), anyString(), any(), any());
        verify(states, times(2)).refresh(sku, resolution, now.plusMinutes(1), now);
    }

    @Test void delayedBoundaryHandlerDelegatesToCurrentRecalculationAndNeverWritesSku() {
        var pricing = mock(GenericBatchPricingService.class);
        ObjectProvider<GenericBatchPricingService> services = mock(ObjectProvider.class);
        when(services.getObject()).thenReturn(pricing);
        var handler = new SuggestedPriceBoundaryTimerHandler(services);
        handler.handle(null, new ObjectMapper().createObjectNode().put("platformSkuId", 7L).put("offerVersionId", 999L));
        verify(pricing).recalculatePlatformSku(new PricingDependencyChangedEvent(7L));
        assertThatThrownBy(() -> handler.handle(null, new ObjectMapper().createObjectNode().put("platformSkuId", -1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void timerCreatesIndependentContextAndRestoresWorkerContextEvenOnFailure() {
        var pricing = mock(GenericBatchPricingService.class);
        ObjectProvider<GenericBatchPricingService> services = mock(ObjectProvider.class);
        when(services.getObject()).thenReturn(pricing);
        var handler = new SuggestedPriceBoundaryTimerHandler(services);
        var worker = java.util.Map.of("traceId", "worker", "custom", "preserve");
        org.slf4j.MDC.setContextMap(worker);
        try {
            doAnswer(call -> {
                assertThat(org.slf4j.MDC.get("traceId")).isNotBlank().isNotEqualTo("worker");
                assertThat(org.slf4j.MDC.get("executionId")).isNotBlank();
                assertThat(org.slf4j.MDC.get("taskNo")).isEqualTo("TASK-1");
                assertThat(org.slf4j.MDC.get("originTraceId")).isNull();
                throw new IllegalStateException("database failure");
            }).when(pricing).recalculatePlatformSku(any());
            assertThatThrownBy(() -> handler.handle(new com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext(
                    "TASK-1", 1L, 1, 1L, "worker"), new ObjectMapper().createObjectNode().put("platformSkuId", 7L)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("database failure");
            assertThat(org.slf4j.MDC.getCopyOfContextMap()).isEqualTo(worker);
        } finally { org.slf4j.MDC.clear(); }
    }

    @Test void missingReferencedOfferVersionIsSystemFailureInsteadOfPending() {
        var resolver=mock(SuggestedSalePriceResolver.class); var states=mock(SuggestedSalePriceStateService.class);
        var offers=mock(SupplierOfferMapper.class); var versions=mock(SupplierOfferVersionMapper.class);
        var sku=PlatformSkuDO.builder().id(1L).marketPrice(10000L).suggestedSalePrice(0L)
                .suggestedSalePriceRuleJson(new SuggestedSalePriceRule(SuggestedSalePriceRule.Mode.CUSTOM,
                        SuggestedSalePriceRule.Base.SUPPLY_PRICE,new BigDecimal("110"),SuggestedSalePriceRule.Operator.ADD,
                        BigDecimal.ONE,null,null)).build();
        var now=LocalDateTime.of(2026,10,19,12,0);
        when(resolver.resolve(sku,now)).thenReturn(SuggestedSalePriceResolver.evaluate(sku,null,null,now));
        when(offers.selectList(any())).thenReturn(List.of(SupplierOfferDO.builder().id(2L).currentVersionId(3L).build()));
        when(versions.selectBatchIds(anyCollection())).thenReturn(List.of());
        var service=new SuggestedSalePriceCoordinator(resolver,states,offers,versions,mock(SupplyChainDatabaseClock.class),
                new ObjectMapper(),mock(ObjectProvider.class));
        assertThatThrownBy(() -> service.refresh(sku,now)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version");
        verifyNoInteractions(states);
    }
}
