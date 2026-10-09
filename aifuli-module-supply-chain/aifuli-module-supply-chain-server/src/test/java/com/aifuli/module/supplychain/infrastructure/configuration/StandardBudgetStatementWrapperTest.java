package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标准预算 Statement 包装器单元测试。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#44-全新statement包装器体系
 */
class StandardBudgetStatementWrapperTest {

    private Statement delegate;
    private StandardBudgetConnectionWrapper connectionWrapper;
    private BudgetContext context;
    private long executionDeadlineE;

    @BeforeEach
    void setUp() {
        delegate = mock(Statement.class);
        connectionWrapper = mock(StandardBudgetConnectionWrapper.class);
        long now = System.nanoTime();
        context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));
        executionDeadlineE = now + TimeUnit.SECONDS.toNanos(6);
    }

    private StandardBudgetStatementWrapper newWrapper() {
        return new StandardBudgetStatementWrapper(delegate, connectionWrapper, context, executionDeadlineE);
    }

    @Test
    void executeInstallsBoundedQueryTimeoutAndSessionLockWait() throws SQLException {
        StandardBudgetStatementWrapper wrapper = newWrapper();

        wrapper.executeQuery("SELECT 1");

        verify(connectionWrapper).ensureSessionLockWaitTimeout(anyInt());
        ArgumentCaptor<Integer> timeoutCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(delegate).setQueryTimeout(timeoutCaptor.capture());
        assertThat(timeoutCaptor.getValue()).isBetween(1, 5);
    }

    @Test
    void expiredBudgetRejectsExecutionBeforeDelegate() throws SQLException {
        StandardBudgetStatementWrapper wrapper =
                new StandardBudgetStatementWrapper(delegate, connectionWrapper, context, System.nanoTime() - 1000);

        assertThatThrownBy(() -> wrapper.execute("SELECT 1")).isInstanceOf(BudgetTimeoutException.class);
        verify(delegate, never()).execute(anyString());
    }

    @Test
    void observedMinPositiveQueryTimeoutIsRespected() throws SQLException {
        StandardBudgetStatementWrapper wrapper = newWrapper();

        wrapper.setQueryTimeout(3);
        wrapper.execute("SELECT 1");

        ArgumentCaptor<Integer> timeoutCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(delegate, atLeastOnce()).setQueryTimeout(timeoutCaptor.capture());
        assertThat(timeoutCaptor.getAllValues()).allMatch(v -> v >= 1 && v <= 3);
    }

    @Test
    void cleanupModeSkipsBudgetGuard() throws SQLException {
        when(connectionWrapper.isCleanupMode()).thenReturn(true);
        StandardBudgetStatementWrapper wrapper =
                new StandardBudgetStatementWrapper(delegate, connectionWrapper, context, System.nanoTime() - 1000);

        wrapper.execute("SELECT 1");

        verify(delegate).execute("SELECT 1");
        verify(delegate, never()).setQueryTimeout(anyInt());
    }

    @Test
    void getConnectionReturnsWrapper() throws SQLException {
        assertThat(newWrapper().getConnection()).isSameAs(connectionWrapper);
    }
}
