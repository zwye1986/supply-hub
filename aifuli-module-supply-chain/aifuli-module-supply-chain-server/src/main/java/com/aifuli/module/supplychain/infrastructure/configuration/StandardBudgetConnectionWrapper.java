package com.aifuli.module.supplychain.infrastructure.configuration;

import lombok.extern.slf4j.Slf4j;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 预算感知 Connection 包装器（Hikari + 标准 JDBC 链路）。
 *
 * <p>依据 {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#43-预算控制连接包装器standardbudgetconnectionwrapper}：</p>
 * <ul>
 *   <li>借用即保存驱动原始 networkTimeout 并安装预算限制 min(7000ms, 剩余预算)；</li>
 *   <li>SESSION innodb_lock_wait_timeout 的读取与 SET 走同一物理连接的封闭非递归 control 通道，不触发外层包装器；</li>
 *   <li>rollback 进入独占 cleanup 模式，回滚与状态恢复不再被业务 E 守卫拒绝（R01.5）；</li>
 *   <li>退出恢复 SESSION 与 networkTimeout，任一恢复失败即 markCleanupFailed 并触发基础设施淘汰协议（evictConnection + abort），严禁重跑。</li>
 * </ul>
 */
@Slf4j
public class StandardBudgetConnectionWrapper implements Connection {

    private final Connection delegate;
    private final BudgetContext context;
    private final long executionDeadlineE;
    private final Consumer<Connection> evictor;
    private final int originalNetworkTimeout;

    private volatile boolean sessionTimeoutInstalled = false;
    private int originalSessionTimeout = 50;
    private volatile boolean cleanupMode = false;

    public StandardBudgetConnectionWrapper(Connection delegate,
                                           BudgetContext context,
                                           long executionDeadlineE,
                                           Consumer<Connection> evictor) {
        this.delegate = delegate;
        this.context = context;
        this.executionDeadlineE = executionDeadlineE;
        this.evictor = evictor;

        int origTimeout = 0;
        try {
            origTimeout = delegate.getNetworkTimeout();
        } catch (Throwable e) {
            log.warn("读取原始 networkTimeout 失败，按 0 处理: {}", e.getMessage());
        }
        this.originalNetworkTimeout = origTimeout;

        try {
            long remaining = Math.min(executionDeadlineE - System.nanoTime(),
                    context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS - System.nanoTime());
            int socketTimeoutMs = (int) Math.min(7000L, Math.max(1L, remaining / 1_000_000L));
            delegate.setNetworkTimeout(Runnable::run, socketTimeoutMs);
        } catch (Throwable e) {
            log.warn("无法安装初始 socket timeout: {}", e.getMessage());
        }
    }

    public boolean isCleanupMode() {
        return cleanupMode;
    }

    /**
     * SESSION innodb_lock_wait_timeout 安装：固定读取原值 + 固定正整数 SET。
     * 全部经同一物理连接执行，绝不触发外层 Statement 包装器（封闭非递归 control 通道）。
     */
    public synchronized void ensureSessionLockWaitTimeout(int targetSec) {
        if (sessionTimeoutInstalled) {
            return;
        }
        try (Statement s = delegate.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
                if (rs != null && rs.next()) {
                    originalSessionTimeout = rs.getInt(1);
                }
            }
            s.execute("SET SESSION innodb_lock_wait_timeout = " + targetSec);
            sessionTimeoutInstalled = true;
        } catch (SQLException e) {
            log.warn("设置 SESSION innodb_lock_wait_timeout 失败: {}", e.getMessage());
        }
    }

    /** 业务 setup / Statement 创建前守卫；cleanup 模式放行（R01.5 双模式分离）。 */
    private void checkGuard() {
        if (cleanupMode) {
            return;
        }
        if (context.isCancelled()) {
            throw new BudgetTimeoutException("连接操作前检测到已取消或中断");
        }
        if (System.nanoTime() >= executionDeadlineE) {
            throw new BudgetTimeoutException("连接操作前检测到已超过预算执行期限 E");
        }
    }

    /** commit 前最后收紧：MyBatis flush 之后的 beforeCommit 守卫（R01.6）。 */
    private void checkCommitGuard() {
        if (cleanupMode) {
            return;
        }
        if (context.isCancelled()) {
            throw new BudgetTimeoutException("commit 前检测到已取消或中断");
        }
        long now = System.nanoTime();
        if (now >= executionDeadlineE || now >= context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS) {
            throw new BudgetTimeoutException("commit 前检测到预算耗尽，拒绝提交");
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkGuard();
        return new StandardBudgetStatementWrapper(delegate.createStatement(), this, context, executionDeadlineE);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkGuard();
        return new StandardBudgetStatementWrapper(delegate.createStatement(resultSetType, resultSetConcurrency),
                this, context, executionDeadlineE);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkGuard();
        return new StandardBudgetStatementWrapper(
                delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(delegate.prepareStatement(sql), this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(delegate.prepareStatement(sql, autoGeneratedKeys),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(delegate.prepareStatement(sql, columnIndexes),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(delegate.prepareStatement(sql, columnNames),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(delegate.prepareStatement(sql, resultSetType, resultSetConcurrency),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkGuard();
        return new StandardBudgetPreparedStatementWrapper(
                delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability),
                this, context, executionDeadlineE);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        checkGuard();
        return new StandardBudgetCallableStatementWrapper(delegate.prepareCall(sql), this, context, executionDeadlineE);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkGuard();
        return new StandardBudgetCallableStatementWrapper(delegate.prepareCall(sql, resultSetType, resultSetConcurrency),
                this, context, executionDeadlineE);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkGuard();
        return new StandardBudgetCallableStatementWrapper(
                delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability),
                this, context, executionDeadlineE);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return delegate.nativeSQL(sql);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkGuard();
        delegate.setAutoCommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return delegate.getAutoCommit();
    }

    @Override
    public void commit() throws SQLException {
        checkCommitGuard();
        delegate.commit();
    }

    @Override
    public void rollback() throws SQLException {
        // 进入独占 cleanup 模式：回滚与状态恢复不再被业务 E 守卫拒绝（R01.5）
        cleanupMode = true;
        delegate.rollback();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        cleanupMode = true;
        delegate.rollback(savepoint);
    }

    @Override
    public void close() throws SQLException {
        cleanupMode = true;
        Throwable cleanupFailure = null;
        boolean alreadyClosed;
        try {
            alreadyClosed = delegate.isClosed();
        } catch (Throwable t) {
            alreadyClosed = false;
            cleanupFailure = t;
        }
        if (!alreadyClosed) {
            if (sessionTimeoutInstalled) {
                try (Statement s = delegate.createStatement()) {
                    s.execute("SET SESSION innodb_lock_wait_timeout = " + originalSessionTimeout);
                } catch (Throwable t) {
                    cleanupFailure = appendSuppressed(cleanupFailure, t);
                }
            }
            try {
                delegate.setNetworkTimeout(Runnable::run, originalNetworkTimeout);
            } catch (Throwable t) {
                cleanupFailure = appendSuppressed(cleanupFailure, t);
            }
        }
        if (cleanupFailure != null) {
            // 任一恢复失败即标记清理失败并物理淘汰，严禁事务重跑
            context.markCleanupFailed();
            log.error("[预算连接关闭] 连接状态恢复失败，触发物理淘汰与池剔除", cleanupFailure);
            try {
                evictor.accept(delegate);
            } catch (Throwable t) {
                log.warn("[预算连接关闭] 物理淘汰异常: {}", t.getMessage());
            } finally {
                try {
                    delegate.close();
                } catch (Throwable t) {
                    log.warn("[预算连接关闭] 淘汰后代理 close 异常: {}", t.getMessage());
                }
            }
            return;
        }
        try {
            delegate.close();
        } catch (SQLException e) {
            context.markCleanupFailed();
            throw e;
        }
    }

    private static Throwable appendSuppressed(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return delegate.isClosed();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return delegate.getMetaData();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkGuard();
        delegate.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return delegate.isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkGuard();
        delegate.setCatalog(catalog);
    }

    @Override
    public String getCatalog() throws SQLException {
        return delegate.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkGuard();
        delegate.setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return delegate.getTransactionIsolation();
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
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return delegate.getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        delegate.setTypeMap(map);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkGuard();
        delegate.setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        return delegate.getHoldability();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        checkGuard();
        return delegate.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        checkGuard();
        return delegate.setSavepoint(name);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        checkGuard();
        delegate.releaseSavepoint(savepoint);
    }

    @Override
    public Clob createClob() throws SQLException {
        return delegate.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        return delegate.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        return delegate.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return delegate.createSQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return delegate.isValid(timeout);
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        delegate.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        delegate.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return delegate.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return delegate.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return delegate.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return delegate.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        checkGuard();
        delegate.setSchema(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        return delegate.getSchema();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        delegate.abort(executor);
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        checkGuard();
        delegate.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return delegate.getNetworkTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return true;
        }
        return delegate.isWrapperFor(iface);
    }
}
