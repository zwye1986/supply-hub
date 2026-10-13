package com.aifuli.module.supplychain.border.fulfillment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aifuli.module.supplychain.infrastructure.identity.OrderNoAllocationService;
import com.aifuli.module.supplychain.infrastructure.identity.OrderNoPurpose;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;

class RechargeFrequencyAcceptanceCoordinatorTest {
    private final FulfillmentAcceptanceLifecycle lifecycle =
            mock(FulfillmentAcceptanceLifecycle.class);
    private final FulfillmentAcceptanceLifecycle.PreparedAcceptance command =
            mock(FulfillmentAcceptanceLifecycle.PreparedAcceptance.class);
    private final OrderNoAllocationService allocator = mock(OrderNoAllocationService.class);

    private final com.aifuli.module.supplychain.infrastructure.identity.PreparedOrderNumbers
            numbers = com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers();
    private final RechargeFrequencyAcceptanceCoordinator coordinator =
            new RechargeFrequencyAcceptanceCoordinator(lifecycle, allocator);

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        when(allocator.prepare(OrderNoPurpose.ORDER_AND_BILL)).thenReturn(numbers);
    }

    @Test
    void newAcceptancePreparesOnePairOutsideBusinessTransaction() {
        coordinator.accept(42L, command);
        verify(allocator).prepare(OrderNoPurpose.ORDER_AND_BILL);
    }

    @Test
    void acceptedReplayDoesNotAllocateNewNumbers() {
        var result = mock(FulfillmentAcceptanceLifecycle.AcceptanceSnapshot.class);
        when(lifecycle.tryReplayAccepted(42L, command)).thenReturn(result);
        clearInvocations(allocator);
        assertThat(coordinator.accept(42L, command)).isSameAs(result);
        verifyNoInteractions(allocator);
    }

    @Test
    void allocationFailureCanReturnTrustedAcceptedWinner() {
        var result = mock(FulfillmentAcceptanceLifecycle.AcceptanceSnapshot.class);
        when(lifecycle.tryReplayAccepted(42L, command)).thenReturn(null, result);
        when(allocator.prepare(OrderNoPurpose.ORDER_AND_BILL))
                .thenThrow(new IllegalStateException("Redis failure"));
        assertThat(coordinator.accept(42L, command)).isSameAs(result);
        verify(lifecycle, never()).accept(anyLong(), any(), any());
    }

    @Test
    void directProjectOrderRetriesThroughDedicatedLifecycleProxyEntry() {
        var conflict =
                new RechargeFrequencyLifecycle.QueueIdentityCreationConflictException(
                        new DuplicateKeyException("identity competition"));
        var result = mock(FulfillmentAcceptanceLifecycle.AcceptanceSnapshot.class);
        when(lifecycle.acceptDirectProjectOrder(42L, command, numbers))
                .thenThrow(conflict)
                .thenReturn(result);
        assertThat(coordinator.acceptDirectProjectOrder(42L, command)).isSameAs(result);
        verify(lifecycle, times(2)).acceptDirectProjectOrder(42L, command, numbers);
        verify(lifecycle, never()).accept(anyLong(), any(), any());
    }

    @Test
    void retriesOnlyIdentityCreationConflictWithSameReceiptAndStopsAtThree() {
        var conflict =
                new RechargeFrequencyLifecycle.QueueIdentityCreationConflictException(
                        new DuplicateKeyException("identity competition"));
        when(lifecycle.accept(42L, command, numbers)).thenThrow(conflict);
        assertThatThrownBy(() -> coordinator.accept(42L, command))
                .isSameAs(conflict)
                .hasCauseInstanceOf(DuplicateKeyException.class);
        verify(lifecycle, times(3)).accept(42L, command, numbers);
    }

    @Test
    void rolledBackIdentityAttemptIsRetriedThroughLifecycleDelegate() {
        var conflict =
                new RechargeFrequencyLifecycle.QueueIdentityCreationConflictException(
                        new DuplicateKeyException("identity competition"));
        var result = mock(FulfillmentAcceptanceLifecycle.AcceptanceSnapshot.class);
        when(lifecycle.accept(42L, command, numbers)).thenThrow(conflict).thenReturn(result);
        assertThat(coordinator.accept(42L, command)).isSameAs(result);
        verify(lifecycle, times(2)).accept(42L, command, numbers);
        verify(allocator).prepare(OrderNoPurpose.ORDER_AND_BILL);
    }

    @Test
    void otherIntegrityFailuresArePropagatedWithoutRetry() {
        var failure = new DuplicateKeyException("unrelated uniqueness");
        when(lifecycle.accept(42L, command, numbers)).thenThrow(failure);
        assertThatThrownBy(() -> coordinator.accept(42L, command)).isSameAs(failure);
        verify(lifecycle).accept(42L, command, numbers);
    }

    @Test
    void outerTransactionIsRejectedBeforeCallingLifecycle() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> coordinator.accept(42L, command))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(lifecycle);
    }

    @Test
    void mysqlErrorCodeStateAndExactIdentityConstraintAreAllRequired() {
        String key =
                "Duplicate entry 'redacted' for key"
                    + " 'sc_recharge_frequency_queue.uk_sc_recharge_frequency_queue_identity'";
        assertThat(
                        RechargeFrequencyLifecycle.isIdentityCompetition(
                                new DuplicateKeyException(
                                        "outer", new SQLException(key, "23000", 1062))))
                .isTrue();
        assertThat(
                        RechargeFrequencyLifecycle.isIdentityCompetition(
                                new SQLException(key, "23000", 3819)))
                .isFalse();
        assertThat(
                        RechargeFrequencyLifecycle.isIdentityCompetition(
                                new SQLException(key, "HY000", 1062)))
                .isFalse();
        assertThat(
                        RechargeFrequencyLifecycle.isIdentityCompetition(
                                new SQLException(
                                        key.replace("queue_identity", "entry_order"),
                                        "23000",
                                        1062)))
                .isFalse();
        assertThat(
                        RechargeFrequencyLifecycle.isIdentityCompetition(
                                new SQLException(
                                        key.replace("queue_identity", "queue_identity_extra"),
                                        "23000",
                                        1062)))
                .isFalse();
    }
}
