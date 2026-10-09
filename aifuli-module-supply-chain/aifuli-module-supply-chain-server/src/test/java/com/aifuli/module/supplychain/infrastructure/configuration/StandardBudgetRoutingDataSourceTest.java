package com.aifuli.module.supplychain.infrastructure.configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 R01 / R02 预算借用、超时守卫、参数安装与恢复/淘汰协议（Hikari + Testcontainers 真实 MySQL）。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#6-测试与多维门禁验证计划
 */
class StandardBudgetRoutingDataSourceTest {

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
        config.setConnectionTimeout(3000);
        config.addDataSourceProperty("connectTimeout", "1500");
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
    void evictedConnectionIsNeverReusedByPool() throws Exception {
        BudgetContext.clear();
        String evictedThreadId;
        try (Connection conn = routingDataSource.getConnection()) {
            // 记录底层 MySQL 会话身份（物理连接可观测证据）
            try (Statement stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT CONNECTION_ID()")) {
                assertThat(rs.next()).isTrue();
                evictedThreadId = rs.getString(1);
            }
            // 直接触发基础设施淘汰协议：Hikari evictConnection + 驱动 abort
            routingDataSource.evictAndAbort(conn);
        }

        // 后续借出的连接绝不复用被淘汰的物理会话（设计 §6.3.1）
        try (Connection conn = routingDataSource.getConnection();
             Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT CONNECTION_ID()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNotEqualTo(evictedThreadId);
        }
    }
}
