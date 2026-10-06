package com.aifuli.module.supplychain.infrastructure.configuration;

import com.alibaba.druid.pool.DruidDataSource;
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
 * 验证 R01 / R02 预算借用、超时守卫、参数安装与恢复/丢弃协议。
 */
class BudgetRoutingDataSourceTest {

    static MySQLContainer<?> mysql;
    static DruidDataSource rawDruid;
    static BudgetRoutingDataSource routingDataSource;

    @BeforeAll
    static void init() {
        mysql = new MySQLContainer<>("mysql:8.4");
        mysql.start();

        rawDruid = new DruidDataSource();
        rawDruid.setUrl(mysql.getJdbcUrl() + "?useSSL=false&autoReconnect=false");
        rawDruid.setUsername(mysql.getUsername());
        rawDruid.setPassword(mysql.getPassword());
        rawDruid.setDriverClassName("com.mysql.cj.jdbc.Driver");
        rawDruid.setConnectTimeout(1500);
        rawDruid.setMaxActive(5);
        rawDruid.setMaxWait(1500);

        routingDataSource = new BudgetRoutingDataSource(rawDruid);
    }

    @AfterAll
    static void cleanup() {
        if (rawDruid != null) {
            rawDruid.close();
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
                assertThat(conn).isInstanceOf(BudgetConnectionWrapper.class);
                BudgetConnectionWrapper wrapper = (BudgetConnectionWrapper) conn;
                assertThat(wrapper.getSocket()).isNotNull();

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
    void budgetContextLateConnectionIsRejectedAndSafelyDiscarded() {
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
}
