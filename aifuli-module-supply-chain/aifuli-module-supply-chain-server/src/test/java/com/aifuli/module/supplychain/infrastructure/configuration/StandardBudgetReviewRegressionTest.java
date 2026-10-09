package com.aifuli.module.supplychain.infrastructure.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 评审负例：设计见迁移设计 §4.3「2026-10-09 代码评审修复协议」及 §4.4。 */
class StandardBudgetReviewRegressionTest {
    private Connection raw;
    private BudgetContext context;
    private Consumer<Connection> evictor;
    private Statement control;
    private ResultSet session;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        raw = mock(Connection.class);
        evictor = mock(Consumer.class);
        context = BudgetContext.create(System.nanoTime(), System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
        when(raw.getNetworkTimeout()).thenReturn(30000);
        control = mock(Statement.class);
        session = mock(ResultSet.class);
        when(raw.createStatement()).thenReturn(control);
        when(control.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")).thenReturn(session);
        when(session.next()).thenReturn(true);
        when(session.getInt(1)).thenReturn(50);
    }

    private StandardBudgetConnectionWrapper connection() throws Exception {
        return new StandardBudgetConnectionWrapper(raw, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6), evictor);
    }

    @Test
    void networkReadFailureIsNotGuessedAndBorrowIsIsolated() throws Exception {
        SQLException cause = new SQLException("network read");
        when(raw.getNetworkTimeout()).thenThrow(cause);
        assertThatThrownBy(this::connection).isSameAs(cause);
        assertThat(context.isCleanupFailed()).isTrue();
        verify(evictor).accept(raw);
        verify(raw).close();
    }

    @Test
    void networkInstallFailureIsNotSwallowed() throws Exception {
        doThrow(new SQLException("install")).when(raw).setNetworkTimeout(any(), anyInt());
        assertThatThrownBy(this::connection).isInstanceOf(SQLException.class).hasMessage("install");
        verify(evictor).accept(raw);
        verify(raw).close();
    }

    @Test
    void shorterOriginalNetworkLimitIsNeverRelaxed() throws Exception {
        when(raw.getNetworkTimeout()).thenReturn(100);
        connection().setNetworkTimeout(Runnable::run, 0);
        var limits = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(raw, atLeastOnce()).setNetworkTimeout(any(), limits.capture());
        assertThat(limits.getAllValues()).allMatch(value -> value > 0 && value <= 100);
    }

    @Test
    void sessionReadFailurePreventsBusinessExecution() throws Exception {
        when(session.next()).thenReturn(false);
        Statement business = mock(Statement.class);
        StandardBudgetConnectionWrapper conn = connection();
        var wrapper = new StandardBudgetStatementWrapper(business, conn, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
        assertThatThrownBy(() -> wrapper.execute("SELECT 1")).isInstanceOf(SQLException.class);
        verify(business, never()).execute(anyString());
    }

    @Test
    void sessionOneSecondIsNotRelaxed() throws Exception {
        when(session.getInt(1)).thenReturn(1);
        connection().ensureSessionLockWaitTimeout(2);
        verify(control, never()).execute("SET SESSION innodb_lock_wait_timeout = 2");
    }

    @Test
    void sessionIsTightenedOnSubsequentExecutionAndControlsHavePositiveTimeout() throws Exception {
        var conn = connection();
        conn.ensureSessionLockWaitTimeout(2);
        conn.ensureSessionLockWaitTimeout(1);
        verify(control).execute("SET SESSION innodb_lock_wait_timeout = 1");
        verify(control, atLeastOnce()).setQueryTimeout(intThat(value -> value > 0));
    }

    @Test
    void halfAppliedSessionSetIsRestoredOnClose() throws Exception {
        doThrow(new SQLException("lost SET response")).when(control)
                .execute("SET SESSION innodb_lock_wait_timeout = 2");
        var conn = connection();
        assertThatThrownBy(() -> conn.ensureSessionLockWaitTimeout(2)).isInstanceOf(SQLException.class);
        conn.close();
        verify(control).execute("SET SESSION innodb_lock_wait_timeout = 50");
    }

    @Test
    void cancellationDuringControlPreventsBusinessSql() throws Exception {
        when(control.executeQuery(anyString())).thenAnswer(call -> {
            context.cancel();
            return session;
        });
        var conn = connection();
        Statement business = mock(Statement.class);
        var wrapper = new StandardBudgetStatementWrapper(business, conn, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
        assertThatThrownBy(() -> wrapper.execute("SELECT 1")).isInstanceOf(BudgetTimeoutException.class);
        verify(business, never()).execute(anyString());
        verify(session).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepare", "metadata", "valid", "autoCommit"})
    void setupReturnedAfterCancellationIsRejected(String operation) throws Exception {
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(raw.prepareStatement(anyString())).thenAnswer(call -> { context.cancel(); return prepared; });
        when(raw.getMetaData()).thenAnswer(call -> { context.cancel(); return mock(DatabaseMetaData.class); });
        when(raw.isValid(anyInt())).thenAnswer(call -> { context.cancel(); return true; });
        when(raw.getAutoCommit()).thenAnswer(call -> { context.cancel(); return true; });
        var conn = connection();
        assertThatThrownBy(() -> {
            switch (operation) {
                case "prepare" -> conn.prepareStatement("SELECT 1");
                case "metadata" -> conn.getMetaData();
                case "valid" -> conn.isValid(1);
                case "autoCommit" -> conn.getAutoCommit();
                default -> throw new AssertionError(operation);
            }
        }).isInstanceOf(BudgetTimeoutException.class);
        if (operation.equals("prepare")) verify(prepared).close();
    }

    @Test
    void unwrapAndMetadataCannotExposeRawConnection() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(raw.getMetaData()).thenReturn(metadata);
        when(metadata.getConnection()).thenReturn(raw);
        when(raw.unwrap(Runnable.class)).thenReturn(mock(Runnable.class));
        var conn = connection();
        assertThatThrownBy(() -> conn.unwrap(Runnable.class)).isInstanceOf(SQLException.class);
        assertThat(conn.getMetaData().getConnection()).isSameAs(conn);
        assertThatThrownBy(() -> conn.getMetaData().unwrap(Runnable.class)).isInstanceOf(SQLException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"autoCommit", "readOnly", "isolation"})
    void managerRestoreFailureRemainsFatalEvenIfCloseSucceeds(String operation) throws Exception {
        var conn = connection();
        conn.rollback();
        doThrow(new SQLException("manager restore")).when(raw).setAutoCommit(true);
        doThrow(new SQLException("manager restore")).when(raw).setReadOnly(false);
        doThrow(new SQLException("manager restore")).when(raw).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        assertThatThrownBy(() -> {
            switch (operation) {
                case "autoCommit" -> conn.setAutoCommit(true);
                case "readOnly" -> conn.setReadOnly(false);
                case "isolation" -> conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                default -> throw new AssertionError(operation);
            }
        }).isInstanceOf(SQLException.class);
        assertThat(context.isCleanupFailed()).isTrue();
        assertThatThrownBy(conn::close).isInstanceOf(SQLException.class);
        verify(evictor).accept(raw);
        verify(raw).close();
    }

    @Test
    void statementOriginalLimitAndCacheStateArePreserved() throws Exception {
        Statement rawStatement = mock(Statement.class);
        when(rawStatement.getQueryTimeout()).thenReturn(1);
        var wrapper = new StandardBudgetStatementWrapper(rawStatement, connection(), context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
        wrapper.setQueryTimeout(5);
        wrapper.setQueryTimeout(0);
        wrapper.execute("SELECT 1");
        verify(rawStatement, never()).setQueryTimeout(5);
        verify(rawStatement, never()).setQueryTimeout(0);
        wrapper.close();
        verify(rawStatement, atLeastOnce()).setQueryTimeout(1);
        verify(rawStatement).close();
    }

    @Test
    void statementRestoreFailurePoisonsConnectionAndStillClosesStatement() throws Exception {
        Statement stmt = mock(Statement.class);
        var conn = connection();
        var wrapper = new StandardBudgetStatementWrapper(stmt, conn, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
        wrapper.execute("SELECT 1");
        doThrow(new SQLException("cache restore")).when(stmt).setQueryTimeout(0);
        assertThatThrownBy(wrapper::close).isInstanceOf(SQLException.class);
        assertThat(context.isCleanupFailed()).isTrue();
        verify(stmt).close();
        assertThatThrownBy(conn::close).isInstanceOf(SQLException.class);
        verify(evictor).accept(raw);
    }

    @Test
    void allLargeUpdateOverloadsDelegateWithGuards() throws Exception {
        Statement stmt = mock(Statement.class);
        var conn = connection();
        var wrapper = new StandardBudgetStatementWrapper(stmt, conn, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
        wrapper.executeLargeUpdate("UPDATE x");
        wrapper.executeLargeUpdate("UPDATE x", Statement.RETURN_GENERATED_KEYS);
        wrapper.executeLargeUpdate("UPDATE x", new int[]{1});
        wrapper.executeLargeUpdate("UPDATE x", new String[]{"id"});
        verify(stmt).executeLargeUpdate("UPDATE x");
        verify(stmt).executeLargeUpdate("UPDATE x", Statement.RETURN_GENERATED_KEYS);
        verify(stmt).executeLargeUpdate("UPDATE x", new int[]{1});
        verify(stmt).executeLargeUpdate("UPDATE x", new String[]{"id"});
        PreparedStatement ps = mock(PreparedStatement.class);
        new StandardBudgetPreparedStatementWrapper(ps, conn, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6)).executeLargeUpdate();
        verify(ps).executeLargeUpdate();
        CallableStatement cs = mock(CallableStatement.class);
        new StandardBudgetCallableStatementWrapper(cs, conn, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6)).executeLargeUpdate();
        verify(cs).executeLargeUpdate();
    }

    @Test
    void slowControlExhaustsExecutionDeadlineWithoutIssuingBusinessSql() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200);
        var conn = new StandardBudgetConnectionWrapper(raw, context, deadline, evictor);
        when(control.executeQuery(anyString())).thenAnswer(call -> {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1300));
            return session;
        });
        Statement business = mock(Statement.class);
        var wrapper = new StandardBudgetStatementWrapper(business, conn, context, deadline);
        assertThatThrownBy(() -> wrapper.execute("SELECT 1")).isInstanceOf(BudgetTimeoutException.class);
        verify(business, never()).execute(anyString());
        verify(session).close();
        conn.close();
    }

    @Test
    void metadataResultSetCannotLeakStatementAndFetchIsBudgetGuarded() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet tables = mock(ResultSet.class);
        when(raw.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(null, null, null, null)).thenReturn(tables);
        when(tables.getStatement()).thenReturn(control);
        var conn = connection();
        ResultSet view = conn.getMetaData().getTables(null, null, null, null);
        assertThat(view.getStatement()).isNull();
        context.cancel();
        assertThatThrownBy(view::next).isInstanceOf(BudgetTimeoutException.class);
        verify(tables, never()).next();
        view.close();
        verify(tables).close();
    }

    @Test
    void statementTimeoutReadFailureClosesRawStatement() throws Exception {
        Statement statement = mock(Statement.class);
        when(statement.getQueryTimeout()).thenThrow(new SQLException("read timeout"));
        var conn = connection();
        assertThatThrownBy(() -> new StandardBudgetStatementWrapper(statement, conn, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6))).isInstanceOf(SQLException.class);
        verify(statement).close();
    }

    @Test
    void connectionCloseRestoresAndClosesStillOpenStatement() throws Exception {
        var conn = connection();
        Statement rawStatement = mock(Statement.class);
        when(rawStatement.getQueryTimeout()).thenReturn(3);
        var wrapper = new StandardBudgetStatementWrapper(rawStatement, conn, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
        wrapper.setQueryTimeout(1);
        conn.close();
        var order = inOrder(rawStatement, raw);
        order.verify(rawStatement).setQueryTimeout(3);
        order.verify(rawStatement).close();
        order.verify(raw).setNetworkTimeout(any(), eq(30000));
        wrapper.close();
        verify(rawStatement).close();
    }

    @Test
    void managerCleanupAfterBeginFailureMustBypassCancellation() throws Exception {
        when(raw.getAutoCommit()).thenReturn(true);
        doAnswer(call -> { context.cancel(); throw new SQLException("setup failed"); }).when(raw).setAutoCommit(false);
        var conn = connection();
        javax.sql.DataSource ds = mock(javax.sql.DataSource.class);
        when(ds.getConnection()).thenReturn(conn);
        var manager = new org.springframework.jdbc.support.JdbcTransactionManager(ds);
        var template = new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThatThrownBy(() -> template.execute(status -> null)).isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);
        verify(raw).close();
    }

    @Test
    void successfulCommitStillAllowsManagerRestoreAfterCancellation() throws Exception {
        var conn = connection();
        conn.commit();
        context.cancel();
        conn.setAutoCommit(true);
        conn.setReadOnly(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(raw).setAutoCommit(true);
        verify(raw).setReadOnly(false);
        conn.close();
        assertThat(context.isCleanupFailed()).isFalse();
    }

    @Test
    void halfSetAndRestoreFailuresKeepBothCausesAndForbidRetry() throws Exception {
        var conn = connection();
        doThrow(new SQLException("SET response lost")).when(control).execute("SET SESSION innodb_lock_wait_timeout = 2");
        assertThatThrownBy(() -> conn.ensureSessionLockWaitTimeout(2)).hasMessage("SET response lost");
        doThrow(new SQLException("SESSION restore")).when(control).execute("SET SESSION innodb_lock_wait_timeout = 50");
        doThrow(new IllegalStateException("eviction failed")).when(evictor).accept(raw);
        assertThatThrownBy(conn::close).hasMessage("SESSION restore").satisfies(failure ->
                assertThat(failure.getSuppressed()).anyMatch(cause -> cause.getMessage().equals("eviction failed")));
        assertThat(context.isCleanupFailed()).isTrue();
        verify(raw).close();
    }

    @Test
    void largeUpdateIsRejectedAfterCancellationBeforeDriver() throws Exception {
        var conn = connection();
        Statement stmt = mock(Statement.class);
        var wrapper = new StandardBudgetStatementWrapper(stmt, conn, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
        context.cancel();
        assertThatThrownBy(() -> wrapper.executeLargeUpdate("UPDATE x")).isInstanceOf(BudgetTimeoutException.class);
        verify(stmt, never()).executeLargeUpdate(anyString());
        conn.close();
    }

    @Test
    void lateSuccessfulCommitStillEntersCleanupForManagerRestore() throws Exception {
        var conn = connection();
        doAnswer(call -> { context.cancel(); return null; }).when(raw).commit();
        assertThatThrownBy(conn::commit).isInstanceOf(BudgetTimeoutException.class);
        conn.setAutoCommit(true);
        conn.setReadOnly(false);
        verify(raw).setAutoCommit(true);
        conn.close();
        assertThat(context.isCleanupFailed()).isFalse();
    }

    @Test
    void failedSavepointRollbackPermanentlyForbidsRetry() throws Exception {
        var conn = connection();
        var savepoint = mock(java.sql.Savepoint.class);
        SQLException failure = new SQLException("savepoint rollback failed");
        doThrow(failure).when(raw).rollback(savepoint);
        assertThatThrownBy(() -> conn.rollback(savepoint)).isSameAs(failure);
        assertThat(context.isCleanupFailed()).isTrue();
        assertThatThrownBy(conn::close).isSameAs(failure);
        verify(evictor).accept(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"statement", "resultSet"})
    void controlCloseFailurePermanentlyForbidsRetry(String resource) throws Exception {
        var conn = connection();
        SQLException failure = new SQLException("control close failed");
        if (resource.equals("statement")) doThrow(failure).when(control).close();
        else doThrow(failure).when(session).close();
        assertThatThrownBy(() -> conn.ensureSessionLockWaitTimeout(2)).isSameAs(failure);
        assertThat(context.isCleanupFailed()).isTrue();
        assertThatThrownBy(conn::close).isSameAs(failure);
        verify(evictor).accept(raw);
    }
}
