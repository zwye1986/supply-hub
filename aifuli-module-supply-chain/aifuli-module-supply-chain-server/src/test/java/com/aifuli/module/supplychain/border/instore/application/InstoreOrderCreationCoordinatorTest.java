package com.aifuli.module.supplychain.border.instore.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aifuli.module.supplychain.infrastructure.identity.*;
import com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;

class InstoreOrderCreationCoordinatorTest {
    private final InstoreChannelLifecycle lifecycle = mock(InstoreChannelLifecycle.class);
    private final OrderNoAllocationService allocator = mock(OrderNoAllocationService.class);
    private final InstoreChannelLifecycle.CreatePaymentOrderCommand command =
            mock(InstoreChannelLifecycle.CreatePaymentOrderCommand.class);
    private final InstoreChannelLifecycle.PreparedPayment result =
            mock(InstoreChannelLifecycle.PreparedPayment.class);
    private final PreparedOrderNumbers numbers = OrderNoTestConfiguration.numbers();
    private final InstoreOrderCreationCoordinator coordinator =
            new InstoreOrderCreationCoordinator(lifecycle, allocator);

    @Test
    void newOrderUsesPreparedIdentityExactlyOnce() {
        when(allocator.prepare(OrderNoPurpose.ORDER_ONLY)).thenReturn(numbers);
        when(lifecycle.createPaymentOrder(command, numbers)).thenReturn(result);
        assertThat(coordinator.create(command)).isSameAs(result);
        var order = inOrder(lifecycle, allocator);
        order.verify(lifecycle).tryReplayPayment(command);
        order.verify(allocator).prepare(OrderNoPurpose.ORDER_ONLY);
        order.verify(lifecycle).createPaymentOrder(command, numbers);
        verifyNoMoreInteractions(allocator);
    }

    @Test
    void persistedReplayNeedsNoRedisOrReservation() {
        when(lifecycle.tryReplayPayment(command)).thenReturn(result);
        assertThat(coordinator.create(command)).isSameAs(result);
        verifyNoInteractions(allocator);
    }

    @Test
    void allocationFailureCanReplayOnlyTrustedCommittedWinner() {
        when(lifecycle.tryReplayPayment(command)).thenReturn(null, result);
        when(allocator.prepare(OrderNoPurpose.ORDER_ONLY))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        assertThat(coordinator.create(command)).isSameAs(result);
        verify(lifecycle, never()).createPaymentOrder(any(), any());
    }

    @Test
    void failedTrustedReadPreservesOriginalCause() {
        var failure = new IllegalStateException("Redis uncertain");
        var read = new IllegalStateException("MySQL unavailable");
        when(lifecycle.tryReplayPayment(command)).thenReturn(null).thenThrow(read);
        when(allocator.prepare(OrderNoPurpose.ORDER_ONLY)).thenThrow(failure);
        assertThatThrownBy(() -> coordinator.create(command)).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(read);
    }

    @Test
    void knownPermanentKeyRaceReplaysAfterRollback() {
        var failure = duplicate("uk_sc_instore_flow_trade");
        when(lifecycle.tryReplayPayment(command)).thenReturn(null, result);
        when(allocator.prepare(OrderNoPurpose.ORDER_ONLY)).thenReturn(numbers);
        when(lifecycle.createPaymentOrder(command, numbers)).thenThrow(failure);
        assertThat(coordinator.create(command)).isSameAs(result);
        verify(lifecycle).createPaymentOrder(command, numbers);
    }

    @Test
    void totalOrderPermanentKeyRaceReplaysOnlyMatchingCommittedFlow() {
        var failure = duplicate("uk_sc_order_legacy_key");
        when(lifecycle.tryReplayPayment(command)).thenReturn(null, result);
        when(allocator.prepare(OrderNoPurpose.ORDER_ONLY)).thenReturn(numbers);
        when(lifecycle.createPaymentOrder(command, numbers)).thenThrow(failure);
        assertThat(coordinator.create(command)).isSameAs(result);
        verify(lifecycle).createPaymentOrder(command, numbers);
    }

    @Test
    void totalOrderConflictWithoutTrustedFlowPropagatesOriginalFailure() {
        var failure = duplicate("uk_sc_order_legacy_key");
        when(allocator.prepare(OrderNoPurpose.ORDER_ONLY)).thenReturn(numbers);
        when(lifecycle.createPaymentOrder(command, numbers)).thenThrow(failure);
        assertThatThrownBy(() -> coordinator.create(command)).isSameAs(failure);
        verify(lifecycle, times(2)).tryReplayPayment(command);
        verify(allocator).prepare(OrderNoPurpose.ORDER_ONLY);
    }

    @Test
    void orderNumberUniquenessConflictNeverReallocatesOrReplays() {
        var failure = duplicate("uk_sc_order_no");
        when(allocator.prepare(OrderNoPurpose.ORDER_ONLY)).thenReturn(numbers);
        when(lifecycle.createPaymentOrder(command, numbers)).thenThrow(failure);
        assertThatThrownBy(() -> coordinator.create(command)).isSameAs(failure);
        verify(lifecycle).tryReplayPayment(command);
        verify(allocator).prepare(OrderNoPurpose.ORDER_ONLY);
    }

    @Test
    void uncertainCommitDoesNotReadWinnerOrCreateAgain() {
        var failure = new IllegalStateException("commit uncertain");
        when(allocator.prepare(OrderNoPurpose.ORDER_ONLY)).thenReturn(numbers);
        when(lifecycle.createPaymentOrder(command, numbers)).thenThrow(failure);
        assertThatThrownBy(() -> coordinator.create(command)).isSameAs(failure);
        verify(lifecycle).tryReplayPayment(command);
        verify(allocator).prepare(OrderNoPurpose.ORDER_ONLY);
    }

    @Test
    void outerTransactionRejectedBeforeAnyProbe() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> coordinator.create(command))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(lifecycle, allocator);
    }

    @Test
    void keyNameErrorCodeAndStateMustAllMatchExactly() {
        String key = "uk_sc_instore_flow_trade";
        assertThat(OrderCreationSupport.isKnownBusinessKey(duplicate(key), key)).isTrue();
        assertThat(OrderCreationSupport.isKnownBusinessKey(duplicate(key + "_extra"), key))
                .isFalse();
        assertThat(
                        OrderCreationSupport.isKnownBusinessKey(
                                new SQLException("for key '" + key + "'", "HY000", 1062), key))
                .isFalse();
        assertThat(
                        OrderCreationSupport.isKnownBusinessKey(
                                new SQLException("for key '" + key + "'", "23000", 3819), key))
                .isFalse();
    }

    private DuplicateKeyException duplicate(String key) {
        return new DuplicateKeyException(
                "outer",
                new SQLException(
                        "Duplicate entry 'redacted' for key 'table." + key + "'", "23000", 1062));
    }
}
