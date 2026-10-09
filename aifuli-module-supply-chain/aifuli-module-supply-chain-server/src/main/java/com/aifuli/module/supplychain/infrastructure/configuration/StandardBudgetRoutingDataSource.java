package com.aifuli.module.supplychain.infrastructure.configuration;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;

/**
 * 供应链中台预算路由数据源（Hikari + 标准 JDBC 链路）。
 *
 * <p>依据 {@code docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#42-预算控制路由数据源standardbudgetroutingdatasource}：</p>
 * <ul>
 *   <li>无预算上下文时透明委派底层 Hikari 池，保持非预算原语义；</li>
 *   <li>有预算上下文时：借前准入核验（R &lt; 3500ms 拒绝）、借后首检（取消/超期/floor 不足 1 秒即判定迟到连接），
 *       迟到连接触发基础设施淘汰协议（Hikari evictConnection + 驱动 abort），绝不暴露给事务管理器；</li>
 *   <li>实现 {@link DisposableBean} 与 {@link AutoCloseable}，负责唯一池的幂等关闭。</li>
 * </ul>
 */
public class StandardBudgetRoutingDataSource implements DataSource, DisposableBean, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StandardBudgetRoutingDataSource.class);

    private final HikariDataSource rawDataSource;

    public StandardBudgetRoutingDataSource(HikariDataSource rawDataSource) {
        this.rawDataSource = Objects.requireNonNull(rawDataSource, "rawDataSource must not be null");
    }

    public HikariDataSource getRawDataSource() {
        return rawDataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        BudgetContext context = BudgetContext.current();
        if (context == null) {
            return rawDataSource.getConnection();
        }
        return getBudgetConnection(context);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        BudgetContext context = BudgetContext.current();
        if (context == null) {
            return rawDataSource.getConnection(username, password);
        }
        return getBudgetConnection(context);
    }

    private Connection getBudgetConnection(BudgetContext context) throws SQLException {
        // 1. 借前准入核验：R < 3500ms 或 floor((R-C)/1s) < 1 直接拒绝
        context.checkAdmission();

        // 2. 借出连接：池借用等待由 Hikari connectionTimeout 保障，预算截断由借后首检兜底
        Connection rawConn = rawDataSource.getConnection();

        // 3. 借后首检（防迟到连接）：取消、超期、floor 不足 1 秒均判定迟到，触发淘汰协议
        long remaining = context.remainingNanos();
        long floorSec = (long) Math.floor((remaining - BudgetContext.CLEANUP_RESERVE_NANOS) / 1_000_000_000.0);
        if (context.isCancelled() || remaining < BudgetContext.ADMISSION_THRESHOLD_NANOS || floorSec < 1) {
            evictAndAbort(rawConn);
            throw new BudgetTimeoutException("借连接返回后检测到已超期或取消，拒绝暴露给事务管理器: remainingMs="
                    + (remaining / 1_000_000L));
        }

        // 4. 首检通过：计算执行期限 E = min(commandDeadline - C, acquiredNano + 6s)
        long acquiredNano = System.nanoTime();
        long executionDeadlineE = Math.min(context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS,
                acquiredNano + 6_000_000_000L);

        return new StandardBudgetConnectionWrapper(rawConn, context, executionDeadlineE, this::evictAndAbort);
    }

    /**
     * 基础设施级淘汰协议：先经 Hikari 公开 API 将池条目标记 REMOVED 并回收容量，
     * 再对驱动底座执行标准 abort 强杀底层 Socket，绝不放回池中复用。
     */
    public void evictAndAbort(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            rawDataSource.evictConnection(conn);
        } catch (Throwable t) {
            log.warn("[连接池淘汰] Hikari evictConnection 失败 message={}", t.getMessage(), t);
        } finally {
            try {
                conn.abort(Runnable::run);
            } catch (Throwable t) {
                log.error("[连接池淘汰] 物理连接 abort 异常", t);
            }
        }
    }

    @Override
    public void destroy() {
        if (!rawDataSource.isClosed()) {
            rawDataSource.close();
        }
    }

    @Override
    public void close() {
        destroy();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return rawDataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        rawDataSource.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        rawDataSource.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return rawDataSource.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return rawDataSource.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        // HikariDataSource 不实现 java.sql.Wrapper，显式支持受控诊断解包
        if (iface.isInstance(rawDataSource)) {
            return iface.cast(rawDataSource);
        }
        throw new SQLException("无法解包为 " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || iface.isInstance(rawDataSource);
    }
}
