package com.aifuli.module.supplychain.infrastructure.configuration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

/**
 * 预算感知 Statement 包装器（Hikari + 标准 JDBC 链路）。
 * 依据 {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#44-全新statement包装器体系}：
 * 执行前守卫 queryTimeout = floor(min(5s, E-now, commandDeadline-C-now)/1s)，
 * 保存原值、保留观察到的最小正限制，不足 1 秒拒绝执行；cleanup 仅恢复状态，不发新业务 SQL。
 */
public class StandardBudgetStatementWrapper implements Statement {

    protected final Statement delegate;
    protected final StandardBudgetConnectionWrapper connectionWrapper;
    protected final BudgetContext context;
    protected final long executionDeadlineE;
    private int observedMinPositive = 0;
    private final int originalQueryTimeout;
    private boolean closed;

    public StandardBudgetStatementWrapper(Statement delegate,
                                  StandardBudgetConnectionWrapper connectionWrapper,
                                  BudgetContext context,
                                  long executionDeadlineE) throws SQLException {
        this.delegate = delegate;
        this.connectionWrapper = connectionWrapper;
        this.context = context;
        this.executionDeadlineE = executionDeadlineE;
        try {
            connectionWrapper.beforeOperation();
            this.originalQueryTimeout = delegate.getQueryTimeout();
            connectionWrapper.afterOperation();
            this.observedMinPositive = originalQueryTimeout;
            connectionWrapper.register(this);
        } catch (SQLException | RuntimeException failure) {
            try { delegate.close(); }
            catch (SQLException | RuntimeException closeFailure) {
                connectionWrapper.recordCleanupFailure(closeFailure);
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    protected void beforeExecute() throws SQLException {
        connectionWrapper.requireBusinessMode();
        int q = queryBudgetSeconds();
        connectionWrapper.ensureSessionLockWaitTimeout(Math.min(2, q));
        // control 可以消耗预算，安装之后必须重新计算 q，不能沿用旧值。
        connectionWrapper.beforeOperation();
        q = queryBudgetSeconds();
        int effectiveTimeout = observedMinPositive > 0 ? Math.min(observedMinPositive, q) : q;
        delegate.setQueryTimeout(effectiveTimeout);
        observedMinPositive = effectiveTimeout;
        connectionWrapper.beforeOperation();
        queryBudgetSeconds();
    }

    private int queryBudgetSeconds() {
        if (context.isCancelled()) {
            throw new BudgetTimeoutException("语句执行前检测到已取消或中断");
        }
        long now = System.nanoTime();
        if (now >= executionDeadlineE || now >= context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS) {
            throw new BudgetTimeoutException("语句执行前检测到已超过预算期限");
        }
        long remaining = Math.min(executionDeadlineE - now,
                context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS - now);
        int q = (int) Math.floor(Math.min(5_000_000_000L, remaining) / 1_000_000_000.0);
        if (q < 1) {
            throw new BudgetTimeoutException("语句执行前可用 queryTimeout 预算不足 1 秒");
        }

        return q;
    }

    /** 所有执行重载共享此入口：返回后检查，迟到 ResultSet 不泄漏。 */
    protected <T> T executeGuarded(StandardBudgetConnectionWrapper.SqlSupplier<T> action) throws SQLException {
        beforeExecute();
        T value = action.get();
        try {
            connectionWrapper.afterOperation();
            if (value instanceof ResultSet result) {
                @SuppressWarnings("unchecked") T wrapped = (T) StandardBudgetJdbcViews.resultSet(result, connectionWrapper, this);
                return wrapped;
            }
            return value;
        } catch (RuntimeException failure) {
            if (value instanceof ResultSet result) {
                try { result.close(); } catch (SQLException closeFailure) {
                    connectionWrapper.recordCleanupFailure(closeFailure);
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        if (seconds < 0) throw new SQLException("queryTimeout 不能为负数");
        if (seconds > 0) {
            if (observedMinPositive <= 0 || seconds < observedMinPositive) {
                observedMinPositive = seconds;
            }
        }
        delegate.setQueryTimeout(observedMinPositive > 0 ? observedMinPositive : seconds);
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return delegate.getQueryTimeout();
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        return executeGuarded(() -> delegate.executeQuery(sql));
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return executeGuarded(() -> delegate.executeUpdate(sql));
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        return executeGuarded(() -> delegate.execute(sql));
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return executeGuarded(delegate::executeBatch);
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        return executeGuarded(delegate::executeLargeBatch);
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeGuarded(() -> delegate.executeUpdate(sql, autoGeneratedKeys));
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeGuarded(() -> delegate.executeUpdate(sql, columnIndexes));
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeGuarded(() -> delegate.executeUpdate(sql, columnNames));
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return executeGuarded(() -> delegate.execute(sql, autoGeneratedKeys));
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return executeGuarded(() -> delegate.execute(sql, columnIndexes));
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return executeGuarded(() -> delegate.execute(sql, columnNames));
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException { return executeGuarded(() -> delegate.executeLargeUpdate(sql)); }

    @Override
    public long executeLargeUpdate(String sql, int keys) throws SQLException { return executeGuarded(() -> delegate.executeLargeUpdate(sql, keys)); }

    @Override
    public long executeLargeUpdate(String sql, int[] indexes) throws SQLException { return executeGuarded(() -> delegate.executeLargeUpdate(sql, indexes)); }

    @Override
    public long executeLargeUpdate(String sql, String[] names) throws SQLException { return executeGuarded(() -> delegate.executeLargeUpdate(sql, names)); }

    @Override
    public void close() throws SQLException {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        try { delegate.setQueryTimeout(originalQueryTimeout); }
        catch (SQLException | RuntimeException restoreFailure) { failure = restoreFailure; }
        try { delegate.close(); }
        catch (SQLException | RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else if (failure != closeFailure) failure.addSuppressed(closeFailure);
        } finally { connectionWrapper.unregister(this); }
        if (failure != null) {
            connectionWrapper.recordCleanupFailure(failure);
            if (failure instanceof SQLException sql) throw sql;
            throw new SQLException("Statement 超时恢复或关闭失败", failure);
        }
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return delegate.getMaxFieldSize();
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        delegate.setMaxFieldSize(max);
    }

    @Override
    public int getMaxRows() throws SQLException {
        return delegate.getMaxRows();
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        delegate.setMaxRows(max);
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        delegate.setEscapeProcessing(enable);
    }

    @Override
    public void cancel() throws SQLException {
        delegate.cancel();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return delegate.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        delegate.clearWarnings();
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        delegate.setCursorName(name);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return StandardBudgetJdbcViews.resultSet(connectionWrapper.guarded(delegate::getResultSet), connectionWrapper, this);
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return delegate.getUpdateCount();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return connectionWrapper.guarded(delegate::getMoreResults);
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        delegate.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return delegate.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        delegate.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return delegate.getFetchSize();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return delegate.getResultSetConcurrency();
    }

    @Override
    public int getResultSetType() throws SQLException {
        return delegate.getResultSetType();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        delegate.addBatch(sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        delegate.clearBatch();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connectionWrapper;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return connectionWrapper.guarded(() -> delegate.getMoreResults(current));
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return StandardBudgetJdbcViews.resultSet(connectionWrapper.guarded(delegate::getGeneratedKeys), connectionWrapper, this);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return delegate.getResultSetHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return delegate.isClosed();
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        delegate.setPoolable(poolable);
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return delegate.isPoolable();
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        delegate.closeOnCompletion();
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return delegate.isCloseOnCompletion();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("预算语句禁止解包到底层驱动: " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return true;
        }
        return false;
    }
}
