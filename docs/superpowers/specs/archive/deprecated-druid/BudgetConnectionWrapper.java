package com.aifuli.module.supplychain.infrastructure.configuration;

import com.alibaba.druid.pool.DruidConnectionHolder;
import com.alibaba.druid.pool.DruidPooledConnection;
import lombok.extern.slf4j.Slf4j;

import java.net.Socket;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
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

/**
 * 预算感知 Connection 包装器。
 * 依据 R01 / R02 规范：
 * - 拦截连接 setup、Statement 创建，注入超时守卫与 SESSION innodb_lock_wait_timeout；
 * - 退出时恢复局部设置，恢复失败直接 Socket.close() 并 abortInternal()，标记 cleanupFailed 且禁重试。
 */
@Slf4j
public class BudgetConnectionWrapper implements Connection {

    private final DruidPooledConnection pooled;
    private final DruidConnectionHolder holder;
    private final Connection physical;
    private final Socket socket;
    private final BudgetContext context;
    private final long executionDeadlineE;
    private final int originalNetworkTimeout;

    private boolean sessionTimeoutInstalled = false;
    private int originalSessionTimeout = 50;

    public BudgetConnectionWrapper(DruidPooledConnection pooled,
                                   DruidConnectionHolder holder,
                                   Connection physical,
                                   Socket socket,
                                   BudgetContext context,
                                   long executionDeadlineE) {
        this.pooled = pooled;
        this.holder = holder;
        this.physical = physical;
        this.socket = socket;
        this.context = context;
        this.executionDeadlineE = executionDeadlineE;

        int origTimeout = 0;
        try {
            origTimeout = pooled.getNetworkTimeout();
        } catch (Throwable ignored) {}
        this.originalNetworkTimeout = origTimeout;

        // 安装初始 socket timeout
        try {
            long remaining = Math.min(executionDeadlineE - System.nanoTime(),
                    context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS - System.nanoTime());
            int socketTimeoutMs = (int) Math.min(7000L, Math.max(1L, remaining / 1_000_000L));
            pooled.setNetworkTimeout(Runnable::run, socketTimeoutMs);
        } catch (Throwable e) {
            log.warn("无法安装初始 socket timeout: {}", e.getMessage());
        }
    }

    public Socket getSocket() {
        return socket;
    }

    public Connection getPhysicalConnection() {
        return physical;
    }

    public DruidConnectionHolder getHolder() {
        return holder;
    }

    public synchronized void ensureSessionLockWaitTimeout(int targetSec) {
        if (!sessionTimeoutInstalled && physical != null) {
            try (Statement s = physical.createStatement()) {
                try (var rs = s.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
                    if (rs.next()) {
                        originalSessionTimeout = rs.getInt(1);
                    }
                }
                s.execute("SET SESSION innodb_lock_wait_timeout = " + targetSec);
                sessionTimeoutInstalled = true;
            } catch (SQLException e) {
                log.warn("设置 SESSION innodb_lock_wait_timeout 失败: {}", e.getMessage());
            }
        }
    }

    private void checkGuard() {
        if (context.isCancelled()) {
            throw new BudgetTimeoutException("连接操作前检测到已取消或中断");
        }
        if (System.nanoTime() >= executionDeadlineE) {
            throw new BudgetTimeoutException("连接操作前检测到已超过预算执行期限 E");
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkGuard();
        return new BudgetStatementWrapper(pooled.createStatement(), this, context, executionDeadlineE);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkGuard();
        return new BudgetStatementWrapper(pooled.createStatement(resultSetType, resultSetConcurrency), this, context, executionDeadlineE);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkGuard();
        return new BudgetStatementWrapper(pooled.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability), this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkGuard();
        return new BudgetPreparedStatementWrapper(pooled.prepareStatement(sql), this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        checkGuard();
        return new BudgetPreparedStatementWrapper(pooled.prepareStatement(sql, autoGeneratedKeys), this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        checkGuard();
        return new BudgetPreparedStatementWrapper(pooled.prepareStatement(sql, columnIndexes), this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        checkGuard();
        return new BudgetPreparedStatementWrapper(pooled.prepareStatement(sql, columnNames), this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkGuard();
        return new BudgetPreparedStatementWrapper(pooled.prepareStatement(sql, resultSetType, resultSetConcurrency), this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkGuard();
        return new BudgetPreparedStatementWrapper(pooled.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability), this, context, executionDeadlineE);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        checkGuard();
        return pooled.prepareCall(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkGuard();
        return pooled.prepareCall(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkGuard();
        return pooled.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return pooled.nativeSQL(sql);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkGuard();
        pooled.setAutoCommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return pooled.getAutoCommit();
    }

    @Override
    public void commit() throws SQLException {
        pooled.commit();
    }

    @Override
    public void rollback() throws SQLException {
        pooled.rollback();
    }

    @Override
    public void close() throws SQLException {
        boolean cleanupSuccess = true;
        try {
            // 恢复 SESSION innodb_lock_wait_timeout
            if (sessionTimeoutInstalled && physical != null && !physical.isClosed()) {
                try (Statement s = physical.createStatement()) {
                    s.execute("SET SESSION innodb_lock_wait_timeout = " + originalSessionTimeout);
                } catch (Throwable e) {
                    cleanupSuccess = false;
                    log.warn("恢复 SESSION innodb_lock_wait_timeout 失败: {}", e.getMessage());
                }
            }
            // 恢复 network timeout
            try {
                pooled.setNetworkTimeout(Runnable::run, originalNetworkTimeout);
            } catch (Throwable e) {
                cleanupSuccess = false;
                log.warn("恢复 networkTimeout 失败: {}", e.getMessage());
            }
        } finally {
            if (!cleanupSuccess) {
                context.markCleanupFailed();
                try {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                } catch (Throwable ignored) {}
                try {
                    MySqlSocketExtractor.abortInternal(physical);
                } catch (Throwable ignored) {}
                try {
                    if (holder != null && holder.getDataSource() != null) {
                        holder.getDataSource().discardConnection(holder);
                    }
                } catch (Throwable ignored) {}
            }
            pooled.close();
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return pooled.isClosed();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return pooled.getMetaData();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        pooled.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return pooled.isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        pooled.setCatalog(catalog);
    }

    @Override
    public String getCatalog() throws SQLException {
        return pooled.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        pooled.setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return pooled.getTransactionIsolation();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return pooled.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        pooled.clearWarnings();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return pooled.getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        pooled.setTypeMap(map);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        pooled.setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        return pooled.getHoldability();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return pooled.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return pooled.setSavepoint(name);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        pooled.rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        pooled.releaseSavepoint(savepoint);
    }

    @Override
    public Clob createClob() throws SQLException {
        return pooled.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        return pooled.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        return pooled.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return pooled.createSQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return pooled.isValid(timeout);
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        pooled.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        pooled.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return pooled.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return pooled.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return pooled.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return pooled.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        pooled.setSchema(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        return pooled.getSchema();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        pooled.abort(executor);
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        pooled.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return pooled.getNetworkTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return pooled.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return true;
        }
        return pooled.isWrapperFor(iface);
    }
}
