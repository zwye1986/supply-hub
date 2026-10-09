package com.aifuli.module.supplychain.infrastructure.configuration;

import com.aifuli.module.supplychain.infrastructure.ledger.LocalLedgerDeadlockException;
import com.aifuli.module.supplychain.infrastructure.ledger.LocalLedgerTransactionalWorkerCoordinator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 Spring manager 捕获恢复异常后仍必须否决重试，见迁移设计 §4.3 / §6.3。 */
class StandardBudgetManagerCleanupTest {
    @ParameterizedTest
    @ValueSource(strings = {"autoCommit", "isolation"})
    @SuppressWarnings("unchecked")
    void swallowedManagerRestoreCannotAllowSecondAttempt(String property) throws Exception {
        Connection raw = mock(Connection.class);
        when(raw.getAutoCommit()).thenReturn(true);
        when(raw.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        BudgetContext context = BudgetContext.create(System.nanoTime(), System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
        Consumer<Connection> evictor = mock(Consumer.class);
        var wrapper = new StandardBudgetConnectionWrapper(raw, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6), evictor);
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(wrapper);
        var manager = new SupplyChainTransactionObservationManager(new JdbcTransactionManager(ds), ds);
        var coordinator = new LocalLedgerTransactionalWorkerCoordinator(manager);
        AtomicInteger executions = new AtomicInteger();
        LocalLedgerDeadlockException deadlock = new LocalLedgerDeadlockException(new SQLException("fixture deadlock", "40001", 1213));
        if (property.equals("autoCommit")) {
            doThrow(new SQLException("restore autoCommit")).when(raw).setAutoCommit(true);
        } else {
            doThrow(new SQLException("restore isolation")).when(raw).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        BudgetContext.set(context);
        try {
            assertThatThrownBy(() -> coordinator.executeWorker("cleanup-fixture", (nonce, attempt) -> {
                executions.incrementAndGet();
                throw deadlock;
            })).isSameAs(deadlock);
            assertThat(context.isCleanupFailed()).isTrue();
            assertThat(executions.get()).isEqualTo(1);
            verify(evictor).accept(raw);
            verify(raw).close();
            assertThat(TransactionSynchronizationManager.hasResource(ds)).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        } finally { BudgetContext.clear(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void readOnlyRestoreFailureIsRememberedOnCommitAndRollback(boolean commit) throws Exception {
        Connection raw = mock(Connection.class);
        when(raw.getAutoCommit()).thenReturn(true);
        BudgetContext context = BudgetContext.create(System.nanoTime(), System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
        var wrapper = new StandardBudgetConnectionWrapper(raw, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6), connection -> { });
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(wrapper);
        var transaction = new TransactionTemplate(new JdbcTransactionManager(ds));
        transaction.setReadOnly(true);
        doThrow(new SQLException("readOnly restore")).when(raw).setReadOnly(false);
        transaction.execute(status -> {
            assertThat(DataSourceUtils.getConnection(ds)).isSameAs(wrapper);
            if (!commit) status.setRollbackOnly();
            return null;
        });
        assertThat(context.isCleanupFailed()).isTrue();
        verify(raw).close();
        assertThat(TransactionSynchronizationManager.hasResource(ds)).isFalse();
    }
}
