package com.aifuli.module.supplychain.infrastructure.configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * 验证 R01 / R02 预算借用、超时守卫、参数安装与恢复/淘汰协议（Hikari + Testcontainers 真实 MySQL）。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#6-测试与多维门禁验证计划
 */
class StandardBudgetRoutingDataSourceIntegrationTest {

    static MySQLContainer<?> mysql;
    static HikariDataSource rawHikari;
    static StandardBudgetRoutingDataSource routingDataSource;

    @BeforeAll
    static void init() {
        mysql = new MySQLContainer<>("mysql:8.4");
        mysql.start();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mysql.getJdbcUrl() + "?useSSL=false&autoReconnect=false");
        config.setUsername(mysql.getUsername());
        config.setPassword(mysql.getPassword());
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setPoolName("supply-chain-test-pool");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(3000);
        config.addDataSourceProperty("connectTimeout", "1500");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("cachePrepStmts", "true");
        rawHikari = new HikariDataSource(config);

        routingDataSource = new StandardBudgetRoutingDataSource(rawHikari);
    }

    @AfterAll
    static void cleanup() {
        if (routingDataSource != null) {
            routingDataSource.close();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void nonBudgetContextReturnsNormalConnection() throws Exception {
        BudgetContext.clear();
        try (Connection conn = routingDataSource.getConnection()) {
            assertThat(conn).isNotNull();
            assertThat(BudgetContext.current()).isNull();
        }
    }

    @Test
    void budgetContextEnforcesAdmissionAndInstallGuards() throws Exception {
        long now = System.nanoTime();
        BudgetContext ctx = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));
        BudgetContext.set(ctx);
        try {
            try (Connection conn = routingDataSource.getConnection()) {
                assertThat(conn).isInstanceOf(StandardBudgetConnectionWrapper.class);

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT 1");
                    // 验证 queryTimeout 受到 <= 5s 约束
                    assertThat(stmt.getQueryTimeout()).isBetween(1, 5);
                }
            }
            assertThat(ctx.isCleanupFailed()).isFalse();
        } finally {
            BudgetContext.clear();
        }
    }

    @Test
    void budgetContextLateConnectionIsRejected() {
        long now = System.nanoTime();
        // 截止时间仅剩 2000ms（< 3500ms 准入门槛）
        BudgetContext expiredCtx = BudgetContext.create(now, now + TimeUnit.MILLISECONDS.toNanos(2000));
        BudgetContext.set(expiredCtx);
        try {
            assertThatThrownBy(() -> routingDataSource.getConnection())
                    .isInstanceOf(BudgetTimeoutException.class)
                    .hasMessageContaining("3500");
        } finally {
            BudgetContext.clear();
        }
    }

    @Test
    void restoreFailureClosesPhysicalSessionNeverReusesItAndRestoresFullCapacity() throws Exception {
        BudgetContext.clear();
        // 同一真实 Hikari 代理仅注入恢复失败；淘汰必须由 wrapper.close 自动触发。
        Connection proxy = spy(rawHikari.getConnection());
        long evictedId = connectionId(proxy);
        int originalTimeout = proxy.getNetworkTimeout();
        BudgetContext context = BudgetContext.create(System.nanoTime(), System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
        var conn = new StandardBudgetConnectionWrapper(proxy, context,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(6), routingDataSource::evictAndAbort);
        try (Statement statement = conn.createStatement()) { statement.execute("SELECT 1"); }
        doThrow(new SQLException("fixture network restore failure")).when(proxy).setNetworkTimeout(any(), eq(originalTimeout));
        assertThatThrownBy(conn::close).isInstanceOf(SQLException.class).hasMessage("fixture network restore failure");
        assertThat(context.isCleanupFailed()).isTrue();
        conn.close(); // 重复 cleanup 不重复扣减 active，也不再次回池。

        // 独立观察会话证明服务端原物理会话消失，不以 isClosed / 池计数替代。
        try (Connection observer = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                try (var statement = observer.prepareStatement("SELECT COUNT(*) FROM information_schema.processlist WHERE ID = ?")) {
                    statement.setLong(1, evictedId);
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getInt(1)).isZero();
                    }
                }
            });
        }

        HikariPoolMXBean pool = rawHikari.getHikariPoolMXBean();
        for (int round = 0; round < 3; round++) {
            List<Connection> borrowed = new ArrayList<>();
            try {
                for (int slot = 0; slot < rawHikari.getMaximumPoolSize(); slot++) {
                    Connection fresh = routingDataSource.getConnection();
                    borrowed.add(fresh);
                    assertThat(connectionId(fresh)).isNotEqualTo(evictedId);
                }
                assertThat(pool.getActiveConnections()).isEqualTo(rawHikari.getMaximumPoolSize());
                assertThat(pool.getTotalConnections()).isEqualTo(rawHikari.getMaximumPoolSize());
            } finally {
                for (Connection borrowedConnection : borrowed) borrowedConnection.close();
            }
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(pool.getActiveConnections()).isZero();
                assertThat(pool.getTotalConnections()).isEqualTo(rawHikari.getMinimumIdle());
                assertThat(pool.getIdleConnections()).isEqualTo(rawHikari.getMinimumIdle());
            });
        }
    }

    @Test
    void serverPreparedCacheAndSessionLimitsAreRestoredOnSamePhysicalConnection() throws Exception {
        BudgetContext.clear();
        try (Connection proxy = rawHikari.getConnection()) {
            long id = connectionId(proxy);
            int originalNetwork = proxy.getNetworkTimeout();
            Connection physical = proxy.unwrap(Connection.class);
            try (Statement setup = proxy.createStatement()) { setup.execute("SET SESSION innodb_lock_wait_timeout = 1"); }
            BudgetContext context = BudgetContext.create(System.nanoTime(), System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
            var conn = new StandardBudgetConnectionWrapper(proxy, context,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(6), routingDataSource::evictAndAbort);
            try (var prepared = conn.prepareStatement("SELECT CONNECTION_ID()")) {
                prepared.setQueryTimeout(1);
                try (var result = prepared.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(id);
                    assertThat(result.getStatement()).isSameAs(prepared);
                    assertThat(result.getStatement().getConnection()).isSameAs(conn);
                }
            }
            try (var cached = proxy.prepareStatement("SELECT CONNECTION_ID()")) {
                assertThat(cached.getQueryTimeout()).isZero();
            }
            try (Statement statement = conn.createStatement(); var result = statement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            conn.close();
            assertThat(context.isCleanupFailed()).isFalse();
            // 提前捕获仅供测试的底座：在同物理会话核对恢复，而非下一借用的随机连接。
            assertThat(physical.getNetworkTimeout()).isEqualTo(originalNetwork);
            try (Statement cleanup = physical.createStatement()) { cleanup.execute("SET SESSION innodb_lock_wait_timeout = 50"); }
        }
    }

    private static long connectionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
