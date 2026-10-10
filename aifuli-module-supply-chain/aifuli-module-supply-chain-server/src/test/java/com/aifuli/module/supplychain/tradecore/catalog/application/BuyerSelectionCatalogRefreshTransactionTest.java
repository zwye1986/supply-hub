package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerCatalogRefreshOutcome;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.BuyerProductPricingInitializationEvent;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 刷新门面事务边界、操作人解析与单次重算委派的纯单元门禁；真实锁序与SQL由集成测试证明。
 * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
 */
class BuyerSelectionCatalogRefreshTransactionTest {

    private final BuyerSelectionCatalogRefreshTransactionalWorker worker =
            mock(BuyerSelectionCatalogRefreshTransactionalWorker.class);
    private final GenericBatchPricingService pricing = mock(GenericBatchPricingService.class);
    private final BuyerCatalogProperties properties = new BuyerCatalogProperties();
    private final BuyerSelectionCatalogRefreshService service =
            new BuyerSelectionCatalogRefreshService(worker, pricing, properties);

    @AfterEach
    void clearTransactionFlags() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void activeTransactionIsRejectedBeforeAnyWorkerCall() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> service.refresh(5L, BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 9L))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(worker, pricing);
    }

    @Test
    void adminSaveRequiresRealOperatorBeforeAnyWorkerCall() {
        for (Long operator : Arrays.asList(null, 0L, -3L)) {
            assertThatThrownBy(() -> service.refresh(5L, BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, operator))
                    .as("operator=%s", operator)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(worker, pricing);
    }

    @Test
    void sweepUsesConfiguredSystemOperatorAndIgnoresCallerIdentity() {
        properties.setSystemOperatorId(77L);
        BuyerSelectionCatalogRefreshResult expected = result(BuyerCatalogRefreshOutcome.PAUSED, null);
        when(worker.refresh(5L, 77L))
                .thenReturn(new BuyerSelectionCatalogRefreshTransactionalWorker.Outcome(expected, null));
        assertThat(service.refresh(5L, BuyerSelectionCatalogRefreshService.Trigger.SWEEP, 999L)).isSameAs(expected);
        verify(worker).refresh(5L, 77L);
        verifyNoInteractions(pricing);
    }

    @Test
    void missingSystemOperatorIsPassedAsNullWithoutInventingZero() {
        BuyerSelectionCatalogRefreshResult expected =
                result(BuyerCatalogRefreshOutcome.DEFERRED,
                        BuyerSelectionCatalogRefreshResult.Reason.SYSTEM_OPERATOR_NOT_CONFIGURED);
        when(worker.refresh(5L, null))
                .thenReturn(new BuyerSelectionCatalogRefreshTransactionalWorker.Outcome(expected, null));
        assertThat(service.refresh(5L, BuyerSelectionCatalogRefreshService.Trigger.ORDER_PREPARE, null))
                .isSameAs(expected);
        verify(worker).refresh(5L, null);
        verifyNoInteractions(pricing);
    }

    @Test
    void businessResultWithoutStaleDependencyDoesNotTouchPricing() {
        BuyerSelectionCatalogRefreshResult expected = result(BuyerCatalogRefreshOutcome.UNCHANGED, null);
        when(worker.refresh(5L, 9L))
                .thenReturn(new BuyerSelectionCatalogRefreshTransactionalWorker.Outcome(expected, null));
        assertThat(service.refresh(5L, BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 9L)).isSameAs(expected);
        verify(worker).refresh(5L, 9L);
        verifyNoInteractions(pricing);
    }

    @Test
    void staleDependencyDelegatesSingleRebuildThenReturnsSecondAttempt() {
        BuyerSelectionCatalogRefreshResult stale = result(BuyerCatalogRefreshOutcome.DEFERRED,
                BuyerSelectionCatalogRefreshResult.Reason.PRICING_DEPENDENCY_STALE);
        BuyerSelectionCatalogRefreshResult updated = result(BuyerCatalogRefreshOutcome.UPDATED, null);
        when(worker.refresh(5L, 9L))
                .thenReturn(new BuyerSelectionCatalogRefreshTransactionalWorker.Outcome(stale, 42L))
                .thenReturn(new BuyerSelectionCatalogRefreshTransactionalWorker.Outcome(updated, null));
        assertThat(service.refresh(5L, BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 9L))
                .isSameAs(updated);
        ArgumentCaptor<BuyerProductPricingInitializationEvent> captor =
                ArgumentCaptor.forClass(BuyerProductPricingInitializationEvent.class);
        verify(pricing).initializeBuyerProduct(captor.capture());
        assertThat(captor.getValue().buyerProductId()).isEqualTo(42L);
        assertThat(captor.getValue().operatorId()).isEqualTo(9L);
        verify(worker, times(2)).refresh(5L, 9L);
    }

    @Test
    void staleDependencyWithoutSystemOperatorDoesNotDelegateRebuild() {
        BuyerSelectionCatalogRefreshResult stale = result(BuyerCatalogRefreshOutcome.DEFERRED,
                BuyerSelectionCatalogRefreshResult.Reason.PRICING_DEPENDENCY_STALE);
        when(worker.refresh(5L, null))
                .thenReturn(new BuyerSelectionCatalogRefreshTransactionalWorker.Outcome(stale, 42L));
        assertThat(service.refresh(5L, BuyerSelectionCatalogRefreshService.Trigger.SWEEP, 999L)).isSameAs(stale);
        verifyNoInteractions(pricing);
    }

    @Test
    void stillStaleAfterRebuildReturnsSecondResultWithoutThirdAttempt() {
        BuyerSelectionCatalogRefreshResult stale = result(BuyerCatalogRefreshOutcome.DEFERRED,
                BuyerSelectionCatalogRefreshResult.Reason.PRICING_DEPENDENCY_STALE);
        when(worker.refresh(5L, 9L))
                .thenReturn(new BuyerSelectionCatalogRefreshTransactionalWorker.Outcome(stale, 42L));
        assertThat(service.refresh(5L, BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 9L)).isSameAs(stale);
        verify(pricing).initializeBuyerProduct(any(BuyerProductPricingInitializationEvent.class));
        verify(worker, times(2)).refresh(5L, 9L);
        verify(worker, never()).refresh(5L, null);
    }

    private static BuyerSelectionCatalogRefreshResult result(BuyerCatalogRefreshOutcome outcome,
                                                             BuyerSelectionCatalogRefreshResult.Reason reason) {
        return new BuyerSelectionCatalogRefreshResult(outcome, BuyerListingStatus.DRAFT,
                ListingAvailability.UNAVAILABLE, reason, LocalDateTime.of(2026, 10, 9, 12, 0));
    }
}
