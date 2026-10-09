package com.aifuli.module.supplychain.infrastructure.configuration;

import com.alibaba.druid.pool.DruidConnectionHolder;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.net.Socket;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * 供应链中台预算路由数据源。
 * 依据 R01 / R02 规范：
 * - 暴露稳定 budget 路由 DataSource 实例，统一资源键；
 * - 无预算上下文时透明委派现有池，保持非预算原语义；
 * - 有预算上下文时，调用 raw.getConnection(1500L)，严格检查 15s deadline 与取消，迟到连接安全关闭且不暴露给 manager；
 * - 返回 BudgetConnectionWrapper，安装 7000ms socket, queryTimeout <= 5s, SESSION innodb_lock_wait_timeout = min(2, q)。
 */
@Slf4j
public class BudgetRoutingDataSource implements DataSource {

    private final DruidDataSource rawDataSource;

    public BudgetRoutingDataSource(DruidDataSource rawDataSource) {
        this.rawDataSource = rawDataSource;
    }

    public DruidDataSource getRawDataSource() {
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
        // 借前准入检查
        context.checkAdmission();

        // 正数 1500L 借用队列等待上限
        Connection rawConn = rawDataSource.getConnection(1500L);
        long now = System.nanoTime();
        long remaining = context.getCommandDeadline() - now;

        // 借后首检：检查取消、总 deadline、业务余量及 floor 取整
        if (context.isCancelled() || remaining < BudgetContext.ADMISSION_THRESHOLD_NANOS) {
            safelyDiscardLateConnection(rawConn);
            throw new BudgetTimeoutException("借连接返回后检测到已超期或取消，拒绝暴露给事务管理器: remainingMs="
                    + (remaining / 1_000_000L));
        }

        long floorSec = (long) Math.floor((remaining - BudgetContext.CLEANUP_RESERVE_NANOS) / 1_000_000_000.0);
        if (floorSec < 1) {
            safelyDiscardLateConnection(rawConn);
            throw new BudgetTimeoutException("借连接后可用预算不足1秒，拒绝暴露给事务管理器: remainingMs="
                    + (remaining / 1_000_000L));
        }

        long acquiredNano = now;
        long executionDeadlineE = Math.min(context.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS,
                acquiredNano + 6_000_000_000L);

        // 提取物理连接与 Socket
        if (rawConn instanceof DruidPooledConnection pooled) {
            DruidConnectionHolder holder = pooled.getConnectionHolder();
            Connection physical = holder != null ? holder.getConnection() : null;
            Socket socket = MySqlSocketExtractor.extractSocket(physical);
            return new BudgetConnectionWrapper(pooled, holder, physical, socket, context, executionDeadlineE);
        }

        return rawConn;
    }

    private void safelyDiscardLateConnection(Connection conn) {
        if (conn == null) return;
        try {
            if (conn instanceof DruidPooledConnection pooled) {
                DruidConnectionHolder holder = pooled.getConnectionHolder();
                Connection physical = holder != null ? holder.getConnection() : null;
                try {
                    pooled.disable(new BudgetTimeoutException("迟到连接安全丢弃"));
                } catch (Throwable ignored) {}
                if (physical != null) {
                    Socket socket = MySqlSocketExtractor.extractSocket(physical);
                    if (socket != null && !socket.isClosed()) {
                        try {
                            socket.close();
                        } catch (Throwable ignored) {}
                    }
                    MySqlSocketExtractor.abortInternal(physical);
                }
                if (holder != null) {
                    try {
                        rawDataSource.discardConnection(holder);
                    } catch (Throwable ignored) {}
                }
            } else {
                conn.close();
            }
        } catch (Throwable e) {
            log.warn("丢弃迟到连接异常: {}", e.getMessage());
        }
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
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return rawDataSource.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return rawDataSource.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return true;
        }
        return rawDataSource.isWrapperFor(iface);
    }
}
