package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标准预算连接包装器单元测试。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#43-预算控制连接包装器standardbudgetconnectionwrapper
 */
class StandardBudgetConnectionWrapperTest {

    private Connection delegate;
    private BudgetContext context;
    @SuppressWarnings("unchecked")
    private Consumer<Connection> evictor;
    private long executionDeadlineE;

    @BeforeEach
    void setUp() throws SQLException {
        delegate = mock(Connection.class);
        when(delegate.getNetworkTimeout()).thenReturn(30000);
        evictor = mock(Consumer.class);
        long now = System.nanoTime();
        context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));
        executionDeadlineE = now + TimeUnit.SECONDS.toNanos(6);
    }

    private StandardBudgetConnectionWrapper newWrapper() throws SQLException {
        return new StandardBudgetConnectionWrapper(delegate, context, executionDeadlineE, evictor);
    }

    @Test
    void constructorInstallsBudgetNetworkTimeout() throws SQLException {
        newWrapper();

        verify(delegate).getNetworkTimeout();
        ArgumentCaptor<Integer> timeoutCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(delegate).setNetworkTimeout(any(), timeoutCaptor.capture());
        assertThat(timeoutCaptor.getValue()).isBetween(1, 7000);
    }

    @Test
    void ensureSessionLockWaitTimeoutUsesControlChannelOnce() throws SQLException {
        Statement rawStatement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(delegate.createStatement()).thenReturn(rawStatement);
        when(rawStatement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(50);

        StandardBudgetConnectionWrapper wrapper = newWrapper();
        wrapper.ensureSessionLockWaitTimeout(2);

        verify(rawStatement).execute("SET SESSION innodb_lock_wait_timeout = 2");
        // 二次调用不重复安装
        wrapper.ensureSessionLockWaitTimeout(2);
        // 读取与 SET 各自创建封闭 control 语句；二次相同目标不再发 SQL。
        verify(delegate, times(2)).createStatement();
    }

    @Test
    void closeRestoresSessionAndNetworkTimeoutHealthyReturn() throws SQLException {
        Statement rawStatement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(delegate.createStatement()).thenReturn(rawStatement);
        when(rawStatement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(50);
        when(delegate.isClosed()).thenReturn(false);

        StandardBudgetConnectionWrapper wrapper = newWrapper();
        wrapper.ensureSessionLockWaitTimeout(2);
        wrapper.close();

        verify(rawStatement).execute("SET SESSION innodb_lock_wait_timeout = 50");
        verify(delegate).setNetworkTimeout(any(), eq(30000));
        verify(delegate).close();
        assertThat(context.isCleanupFailed()).isFalse();
    }

    @Test
    void closeRestoreFailureMarksCleanupFailedAndEvicts() throws SQLException {
        Statement rawStatement = mock(Statement.class);
        when(delegate.createStatement()).thenReturn(rawStatement);
        when(delegate.isClosed()).thenReturn(false);
        StandardBudgetConnectionWrapper wrapper = newWrapper();
        doThrow(new SQLException("network down")).when(delegate).setNetworkTimeout(any(), eq(30000));
        assertThatThrownBy(wrapper::close).isInstanceOf(SQLException.class);

        assertThat(context.isCleanupFailed()).isTrue();
        verify(evictor).accept(delegate);
        verify(delegate).close();
    }

    @Test
    void isClosedProbeFailureMarksCleanupFailedAndEvicts() throws SQLException {
        when(delegate.isClosed()).thenThrow(new SQLException("connection lost"));

        StandardBudgetConnectionWrapper wrapper = newWrapper();
        assertThatThrownBy(wrapper::close).isInstanceOf(SQLException.class);

        assertThat(context.isCleanupFailed()).isTrue();
        verify(evictor).accept(delegate);
        verify(delegate).close();
    }

    @Test
    void commitGuardRejectsExpiredBudget() throws SQLException {
        StandardBudgetConnectionWrapper wrapper = newWrapper();
        context.cancel();

        assertThatThrownBy(wrapper::commit).isInstanceOf(BudgetTimeoutException.class);
        verify(delegate, never()).commit();
    }

    @Test
    void rollbackEntersCleanupModeAndBypassesGuard() throws SQLException {
        StandardBudgetConnectionWrapper wrapper = newWrapper();
        context.cancel();

        wrapper.rollback();

        verify(delegate).rollback();
        assertThat(wrapper.isCleanupMode()).isTrue();
        // cleanup 不发新业务 SQL；manager 状态恢复仍不受取消阻断。
        wrapper.setAutoCommit(true);
        verify(delegate).setAutoCommit(true);
    }

    @Test
    void rollbackToSavepointKeepsBudgetGuard() throws SQLException {
        StandardBudgetConnectionWrapper wrapper = newWrapper();

        wrapper.rollback(mock(Savepoint.class));

        verify(delegate).rollback(any(Savepoint.class));
        assertThat(wrapper.isCleanupMode()).isFalse();
        context.cancel();
        // 回滚到保存点后事务继续执行，预算守卫必须保持有效
        assertThatThrownBy(wrapper::createStatement).isInstanceOf(BudgetTimeoutException.class);
    }

    @Test
    void closeEvictorFailureStillClosesDelegate() throws SQLException {
        when(delegate.isClosed()).thenReturn(false);
        doThrow(new RuntimeException("evict boom")).when(evictor).accept(any());

        StandardBudgetConnectionWrapper wrapper = newWrapper();
        doThrow(new SQLException("network down")).when(delegate).setNetworkTimeout(any(), eq(30000));
        assertThatThrownBy(wrapper::close).isInstanceOf(SQLException.class).satisfies(failure ->
                assertThat(failure.getSuppressed()).anyMatch(cause -> cause.getMessage().equals("evict boom")));

        assertThat(context.isCleanupFailed()).isTrue();
        verify(delegate).close();
    }

    @Test
    void closeHealthyPathCloseFailureMarksCleanupFailed() throws SQLException {
        when(delegate.isClosed()).thenReturn(false);
        doThrow(new SQLException("close fail")).when(delegate).close();

        StandardBudgetConnectionWrapper wrapper = newWrapper();
        assertThatThrownBy(wrapper::close).isInstanceOf(SQLException.class);
        assertThat(context.isCleanupFailed()).isTrue();
    }
}
