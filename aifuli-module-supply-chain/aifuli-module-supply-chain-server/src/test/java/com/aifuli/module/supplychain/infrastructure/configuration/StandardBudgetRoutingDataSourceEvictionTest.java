package com.aifuli.module.supplychain.infrastructure.configuration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 标准预算路由数据源纯单元测试（淘汰协议与生命周期）。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#42-预算控制路由数据源standardbudgetroutingdatasource
 */
class StandardBudgetRoutingDataSourceEvictionTest {

    private HikariDataSource raw;
    private StandardBudgetRoutingDataSource routing;

    @BeforeEach
    void setUp() {
        raw = mock(HikariDataSource.class);
        routing = new StandardBudgetRoutingDataSource(raw);
    }

    @Test
    void nonBudgetContextPassesThroughRawConnection() throws SQLException {
        BudgetContext.clear();
        Connection conn = mock(Connection.class);
        when(raw.getConnection()).thenReturn(conn);

        assertThat(routing.getConnection()).isSameAs(conn);
    }

    @Test
    void shortDeadlineIsRejectedBeforeBorrowing() throws SQLException {
        long now = System.nanoTime();
        BudgetContext.set(BudgetContext.create(now, now + TimeUnit.MILLISECONDS.toNanos(2000)));
        try {
            assertThatThrownBy(routing::getConnection)
                    .isInstanceOf(BudgetTimeoutException.class)
                    .hasMessageContaining("3500");
            verify(raw, never()).getConnection();
        } finally {
            BudgetContext.clear();
        }
    }

    @Test
    void cancelledDuringBorrowTriggersEvictionAndAbort() throws SQLException {
        long now = System.nanoTime();
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));
        Connection conn = mock(Connection.class);
        when(raw.getConnection()).thenAnswer(invocation -> {
            context.cancel();
            return conn;
        });
        BudgetContext.set(context);
        try {
            assertThatThrownBy(routing::getConnection).isInstanceOf(BudgetTimeoutException.class);
            verify(raw).evictConnection(conn);
            verify(conn).abort(any());
        } finally {
            BudgetContext.clear();
        }
    }

    @Test
    void healthyBudgetBorrowReturnsWrappedConnection() throws SQLException {
        long now = System.nanoTime();
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.SECONDS.toNanos(15));
        Connection conn = mock(Connection.class);
        when(conn.getNetworkTimeout()).thenReturn(30000);
        when(raw.getConnection()).thenReturn(conn);
        BudgetContext.set(context);
        try (Connection borrowed = routing.getConnection()) {
            assertThat(borrowed).isInstanceOf(StandardBudgetConnectionWrapper.class);
            verify(conn).setNetworkTimeout(any(), anyInt());
        } finally {
            BudgetContext.clear();
        }
    }

    @Test
    void destroyClosesRawPoolIdempotently() {
        when(raw.isClosed()).thenReturn(false).thenReturn(true);

        routing.destroy();
        routing.destroy();

        verify(raw, times(1)).close();
    }

    @Test
    void evictAndAbortShortCircuitsOnNull() {
        routing.evictAndAbort(null);
        verifyNoInteractions(raw);
    }

    @Test
    void evictConnectionFailureStillAborts() throws SQLException {
        Connection conn = mock(Connection.class);
        doThrow(new IllegalStateException("pool shutdown")).when(raw).evictConnection(conn);

        routing.evictAndAbort(conn);

        verify(conn).abort(any());
    }
}
