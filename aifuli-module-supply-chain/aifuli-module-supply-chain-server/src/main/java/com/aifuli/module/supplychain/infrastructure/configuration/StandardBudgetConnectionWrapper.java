package com.aifuli.module.supplychain.infrastructure.configuration;

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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 预算感知 Connection 包装器（Hikari + 标准 JDBC 链路）。
 *
 * <p>依据 {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#43-预算控制连接包装器standardbudgetconnectionwrapper}：</p>
 * <ul>
 *   <li>借用即保存驱动原始 networkTimeout 并安装预算限制 min(7000ms, 剩余预算)；</li>
 *   <li>SESSION innodb_lock_wait_timeout 的读取与 SET 走同一物理连接的封闭非递归 control 通道，不触发外层包装器；</li>
 *   <li>终结事务的无参 rollback() 与 close() 进入独占 cleanup 模式，回滚与状态恢复不再被业务 E 守卫拒绝（R01.5）；</li>
 *   <li>退出恢复 SESSION 与 networkTimeout，任一恢复失败即 markCleanupFailed 并触发基础设施淘汰协议（evictConnection + abort），严禁重跑。</li>
 * </ul>
 */
public class StandardBudgetConnectionWrapper implements Connection {

    private final Connection delegate;
    private final BudgetContext context;
    private final long executionDeadlineE;
    private final Consumer<Connection> evictor;
    private final int originalNetworkTimeout;

    private Integer originalSessionTimeout;
    private int currentSessionTimeout;
    private boolean sessionDirty;
    private int networkLimit;
    private final Set<StandardBudgetStatementWrapper> statements = new LinkedHashSet<>();
    private Throwable cleanupFailure;
    private Throwable protectionFailure;
    private boolean closed;
    private volatile boolean cleanupMode = false;

    public StandardBudgetConnectionWrapper(Connection delegate,
                                           BudgetContext context,
                                           long executionDeadlineE,
                                           Consumer<Connection> evictor) throws SQLException {
        this.delegate = delegate;
        this.context = context;
        this.executionDeadlineE = executionDeadlineE;
        this.evictor = evictor;

        try {
            this.originalNetworkTimeout = delegate.getNetworkTimeout();
            this.networkLimit = originalNetworkTimeout;
            beforeOperation();
            afterOperation();
        } catch (SQLException | RuntimeException failure) {
            // 构造失败尚无包装器可供 manager 清理，必须在交付之前隔离并关闭。
            context.markCleanupFailed();
            isolateAndClose(failure);
            throw failure;
        }
    }

    public boolean isCleanupMode() {
        return cleanupMode;
    }

    /**
     * SESSION innodb_lock_wait_timeout 安装：固定读取原值 + 固定正整数 SET。
     * 全部经同一物理连接执行，绝不触发外层 Statement 包装器（封闭非递归 control 通道）。
     */
    public synchronized void ensureSessionLockWaitTimeout(int targetSec) throws SQLException {
        requireBusinessMode();
        if (targetSec < 1) {
            throw new SQLException("SESSION 锁等待限制必须为正数");
        }
        try {
            if (originalSessionTimeout == null) {
                try (ControlResource<Statement> resource = new ControlResource<>(guarded(delegate::createStatement))) {
                    Statement s = resource.value;
                    prepareControl(s);
                    try (ControlResource<ResultSet> rows = new ControlResource<>(s.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout"))) {
                        ResultSet rs = rows.value;
                        afterOperation();
                        beforeOperation();
                        if (rs == null || !rs.next()) {
                            throw new SQLException("SESSION innodb_lock_wait_timeout 原值读取为空");
                        }
                        int original = rs.getInt(1);
                        if (rs.wasNull() || original < 1) {
                            throw new SQLException("SESSION innodb_lock_wait_timeout 原值不是正数");
                        }
                        afterOperation();
                        originalSessionTimeout = original;
                        currentSessionTimeout = original;
                    }
                }
            }
            int target = Math.min(currentSessionTimeout, Math.min(targetSec, Math.min(2, queryBudgetSeconds())));
            if (target < currentSessionTimeout) {
                try (ControlResource<Statement> resource = new ControlResource<>(guarded(delegate::createStatement))) {
                    Statement s = resource.value;
                    prepareControl(s);
                    // 发出 SET 前标记：服务器已设置但响应丢失也必须恢复，不能依赖成功返回。
                    sessionDirty = true;
                    s.execute("SET SESSION innodb_lock_wait_timeout = " + target);
                    afterOperation();
                    currentSessionTimeout = target;
                }
            }
            beforeOperation();
        } catch (SQLException | RuntimeException failure) {
            protectionFailure = failure;
            throw failure;
        }
    }

    /** 封闭 control 资源的关闭也属于恢复责任，异常不能只被 try-with-resources 上抛后丢失禁重试标记。 */
    private final class ControlResource<T extends AutoCloseable> implements AutoCloseable {
        private final T value;

        private ControlResource(T value) { this.value = value; }

        @Override
        public void close() throws SQLException {
            if (value == null) return;
            try { value.close(); }
            catch (Exception failure) {
                recordCleanupFailure(failure);
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw asSQLException(failure);
            }
        }
    }

    /** control 只收紧网络和本语句超时，不再递归安装 SESSION。 */
    private void prepareControl(Statement statement) throws SQLException {
        beforeOperation();
        statement.setQueryTimeout(queryBudgetSeconds());
        beforeOperation();
    }

    int queryBudgetSeconds() {
        afterOperation();
        long now = System.nanoTime();
        int q = (int) (Math.min(5_000_000_000L, Math.min(executionDeadlineE - now,
                context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS - now)) / 1_000_000_000L);
        if (q < 1) throw new BudgetTimeoutException("可用 queryTimeout 预算不足 1 秒");
        return q;
    }

    void requireBusinessMode() throws SQLException {
        if (cleanupMode || closed) throw new SQLException("清理阶段禁止执行业务 SQL");
    }

    /** 所有可能发包的入口前动态收紧；cleanup 保持最后有效网络限制。 */
    void beforeOperation() throws SQLException {
        if (cleanupMode) return;
        if (protectionFailure != null) throw new SQLException("预算保护安装曾失败，拒绝继续执行", protectionFailure);
        afterOperation();
        long now = System.nanoTime();
        long remaining = Math.min(executionDeadlineE - now,
                context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS - now);
        int target = (int) Math.min(7000L, Math.max(1L, remaining / 1_000_000L));
        if (networkLimit > 0) target = Math.min(networkLimit, target);
        try {
            delegate.setNetworkTimeout(Runnable::run, target);
            networkLimit = target;
            afterOperation();
        } catch (SQLException | RuntimeException failure) {
            protectionFailure = failure;
            throw failure;
        }
    }

    /** 委派返回后的预算检查；不在此发包，避免守卫递归。 */
    void afterOperation() {
        if (cleanupMode) return;
        if (context.isCancelled()) {
            throw new BudgetTimeoutException("连接操作检测到已取消或中断");
        }
        long now = System.nanoTime();
        if (now >= executionDeadlineE || now >= context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS) {
            throw new BudgetTimeoutException("连接操作检测到已超过预算执行期限 E");
        }
    }

    @FunctionalInterface
    interface SqlSupplier<T> { T get() throws SQLException; }
    @FunctionalInterface
    private interface SqlAction { void run() throws SQLException; }

    <T> T guarded(SqlSupplier<T> action) throws SQLException {
        beforeOperation();
        T result;
        try {
            result = action.get();
        } catch (SQLException | RuntimeException failure) {
            if (cleanupMode) recordCleanupFailure(failure);
            throw failure;
        }
        try {
            afterOperation();
            return result;
        } catch (RuntimeException failure) {
            if (result instanceof AutoCloseable resource) {
                try { resource.close(); } catch (Exception closeFailure) { recordCleanupFailure(closeFailure); failure.addSuppressed(closeFailure); }
            }
            throw failure;
        }
    }

    private void guardedRun(SqlAction action) throws SQLException { guarded(() -> { action.run(); return null; }); }

    synchronized void register(StandardBudgetStatementWrapper statement) { statements.add(statement); }
    synchronized void unregister(StandardBudgetStatementWrapper statement) { statements.remove(statement); }

    synchronized void recordCleanupFailure(Throwable failure) {
        context.markCleanupFailed();
        cleanupFailure = appendSuppressed(cleanupFailure, failure);
    }

    @Override
    public Statement createStatement() throws SQLException {
        return new StandardBudgetStatementWrapper(guarded(delegate::createStatement), this, context, executionDeadlineE);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return new StandardBudgetStatementWrapper(guarded(() -> delegate.createStatement(resultSetType, resultSetConcurrency)),
                this, context, executionDeadlineE);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return new StandardBudgetStatementWrapper(
                guarded(() -> delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability)),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return new StandardBudgetPreparedStatementWrapper(guarded(() -> delegate.prepareStatement(sql)), this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return new StandardBudgetPreparedStatementWrapper(guarded(() -> delegate.prepareStatement(sql, autoGeneratedKeys)),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return new StandardBudgetPreparedStatementWrapper(guarded(() -> delegate.prepareStatement(sql, columnIndexes)),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return new StandardBudgetPreparedStatementWrapper(guarded(() -> delegate.prepareStatement(sql, columnNames)),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return new StandardBudgetPreparedStatementWrapper(guarded(() -> delegate.prepareStatement(sql, resultSetType, resultSetConcurrency)),
                this, context, executionDeadlineE);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return new StandardBudgetPreparedStatementWrapper(
                guarded(() -> delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability)),
                this, context, executionDeadlineE);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        return new StandardBudgetCallableStatementWrapper(guarded(() -> delegate.prepareCall(sql)), this, context, executionDeadlineE);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return new StandardBudgetCallableStatementWrapper(guarded(() -> delegate.prepareCall(sql, resultSetType, resultSetConcurrency)),
                this, context, executionDeadlineE);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return new StandardBudgetCallableStatementWrapper(
                guarded(() -> delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability)),
                this, context, executionDeadlineE);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        return guarded(() -> delegate.nativeSQL(sql));
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        guardedRun(() -> delegate.setAutoCommit(autoCommit));
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return guarded(delegate::getAutoCommit);
    }

    @Override
    public void commit() throws SQLException {
        requireBusinessMode();
        beforeOperation();
        delegate.commit();
        // 驱动已确认提交，后检仍须报告迟到，但 Spring 状态恢复不能再受取消/E 守卫阻断。
        // 设计：docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#2026-10-09-代码评审修复协议优先于下文早期示意代码
        try { afterOperation(); }
        finally { cleanupMode = true; }
    }

    @Override
    public void rollback() throws SQLException {
        // 进入独占 cleanup 模式：回滚与状态恢复不再被业务 E 守卫拒绝（R01.5）
        cleanupMode = true;
        guardedRun(delegate::rollback);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        // 回滚到保存点并不终结事务：事务后续仍继续执行，
        // 预算守卫必须保持有效，不得进入 cleanup 模式（设计 §4.3 仅终结事务的无参 rollback()/close() 进入 cleanup）
        guardedRun(() -> {
            try { delegate.rollback(savepoint); }
            catch (SQLException | RuntimeException failure) {
                recordCleanupFailure(failure);
                throw failure;
            }
        });
    }

    @Override
    public synchronized void close() throws SQLException {
        if (closed) return;
        closed = true;
        cleanupMode = true;
        for (StandardBudgetStatementWrapper statement : Set.copyOf(statements)) {
            try { statement.close(); } catch (SQLException | RuntimeException failure) { recordCleanupFailure(failure); }
        }
        boolean alreadyClosed = false;
        try { alreadyClosed = delegate.isClosed(); } catch (SQLException | RuntimeException failure) { recordCleanupFailure(failure); }
        if (alreadyClosed && (sessionDirty || cleanupFailure != null)) {
            recordCleanupFailure(new SQLException("连接提前关闭，无法确认预算状态恢复"));
        }
        if (!alreadyClosed) {
            if (sessionDirty) {
                try (Statement s = delegate.createStatement()) {
                    // cleanup 不使用业务 E；仍保留最后有效正网络限制及正 queryTimeout。
                    s.setQueryTimeout(Math.max(1, Math.min(5, networkLimit / 1000)));
                    s.execute("SET SESSION innodb_lock_wait_timeout = " + originalSessionTimeout);
                } catch (SQLException | RuntimeException failure) {
                    recordCleanupFailure(failure);
                }
            }
            // 只有其余状态恢复成功才撤下预算网络保护，否则保留正限制直到隔离。
            if (cleanupFailure == null) {
                try { delegate.setNetworkTimeout(Runnable::run, originalNetworkTimeout); }
                catch (SQLException | RuntimeException failure) { recordCleanupFailure(failure); }
            }
        }
        if (cleanupFailure != null) {
            isolateAndClose(cleanupFailure);
            throw asSQLException(cleanupFailure);
        }
        try { delegate.close(); }
        catch (SQLException | RuntimeException failure) {
            recordCleanupFailure(failure);
            isolateAndClose(failure);
            throw failure;
        }
    }

    /** 淘汰/关闭失败仅加入主异常，不在继续上抛的连接层重复记录堆栈。 */
    private void isolateAndClose(Throwable failure) {
        try { evictor.accept(delegate); } catch (RuntimeException evictionFailure) { appendSuppressed(failure, evictionFailure); }
        finally {
            try {
                delegate.close();
            } catch (SQLException | RuntimeException closeFailure) { appendSuppressed(failure, closeFailure); }
        }
    }

    private static SQLException asSQLException(Throwable failure) {
        return failure instanceof SQLException sql ? sql : new SQLException("预算连接清理失败", failure);
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
        return closed || delegate.isClosed();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return StandardBudgetJdbcViews.metadata(guarded(delegate::getMetaData), this);
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        guardedRun(() -> delegate.setReadOnly(readOnly));
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return guarded(delegate::isReadOnly);
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        guardedRun(() -> delegate.setCatalog(catalog));
    }

    @Override
    public String getCatalog() throws SQLException {
        return guarded(delegate::getCatalog);
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        guardedRun(() -> delegate.setTransactionIsolation(level));
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return guarded(delegate::getTransactionIsolation);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return guarded(delegate::getWarnings);
    }

    @Override
    public void clearWarnings() throws SQLException {
        guardedRun(delegate::clearWarnings);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return guarded(delegate::getTypeMap);
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        guardedRun(() -> delegate.setTypeMap(map));
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        guardedRun(() -> delegate.setHoldability(holdability));
    }

    @Override
    public int getHoldability() throws SQLException {
        return guarded(delegate::getHoldability);
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return guarded(delegate::setSavepoint);
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return guarded(() -> delegate.setSavepoint(name));
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        guardedRun(() -> delegate.releaseSavepoint(savepoint));
    }

    @Override
    public Clob createClob() throws SQLException {
        return guarded(delegate::createClob);
    }

    @Override
    public Blob createBlob() throws SQLException {
        return guarded(delegate::createBlob);
    }

    @Override
    public NClob createNClob() throws SQLException {
        return guarded(delegate::createNClob);
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return guarded(delegate::createSQLXML);
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return guarded(() -> delegate.isValid(Math.min(timeout > 0 ? timeout : queryBudgetSeconds(), queryBudgetSeconds())));
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        try { guardedRun(() -> delegate.setClientInfo(name, value)); }
        catch (SQLException failure) { throw new SQLClientInfoException(failure.getMessage(), null, failure); }
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        try { guardedRun(() -> delegate.setClientInfo(properties)); }
        catch (SQLException failure) { throw new SQLClientInfoException(failure.getMessage(), null, failure); }
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return guarded(() -> delegate.getClientInfo(name));
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return guarded(delegate::getClientInfo);
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return guarded(() -> delegate.createArrayOf(typeName, elements));
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return guarded(() -> delegate.createStruct(typeName, attributes));
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        guardedRun(() -> delegate.setSchema(schema));
    }

    @Override
    public String getSchema() throws SQLException {
        return guarded(delegate::getSchema);
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        context.markCleanupFailed();
        cleanupMode = true;
        guardedRun(() -> delegate.abort(executor));
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        if (milliseconds < 0) throw new SQLException("networkTimeout 不能为负数");
        beforeOperation();
        int target = milliseconds > 0 ? Math.min(networkLimit, milliseconds) : networkLimit;
        guardedRun(() -> delegate.setNetworkTimeout(executor, target));
        networkLimit = target;
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return guarded(delegate::getNetworkTimeout);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("预算连接禁止解包到底层驱动: " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return true;
        }
        return false;
    }
}
