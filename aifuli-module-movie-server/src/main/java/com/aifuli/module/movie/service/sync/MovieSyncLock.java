package com.aifuli.module.movie.service.sync;

import lombok.RequiredArgsConstructor;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * 电影同步跨实例互斥；命名锁由独立 JDBC 会话持有，不与业务事务混用。
 * 设计：docs/superpowers/specs/2026-09-16-movie-supply-schedule-design.md#5-同步调度与恢复
 */
@Component
@RequiredArgsConstructor
public class MovieSyncLock {
    /** 会话锁探测不得无限占用调度线程，单位秒。 */
    private static final int LOCK_QUERY_TIMEOUT_SECONDS = 5;
    /** 当前进程唯一的电影数据源，多类别并行时连接池建议至少十个连接。 */
    private final DataSource dataSource;

    /**
     * 按数据类别非阻塞抢锁；锁忙抛出明确失败，不把手工初始化跳过阶段当作成功。
     * action 中的守卫须在写入前调用，不同类别可并行，热映/待映共用 FILM。
     * 连接必须物理淘汰，代理 close 不保证释放会话锁；守卫不等于跨库写入栅栏。
     */
    public void execute(MovieSyncScope scope, Consumer<Runnable> action) {
        java.util.Objects.requireNonNull(scope, "电影同步锁范围不能为空");
        try {
            // 当前电影进程使用 JDBC starter 的 Hikari；不允许未知池退回仅代理 close 的语义。
            HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
            try (Connection connection = pool.getConnection();
                 HeldLock held = new HeldLock(connection, pool)) {
                String schema = connection.getCatalog();
                if (schema == null || schema.isBlank()) {
                    throw new IllegalStateException("电影数据源必须指定独立数据库");
                }
                // MySQL 命名锁名称上限为 64 字符，用确定性 UUID 区分同服务器的电影环境库。
                String name = "movie-supply:" + scope.name() + ":"
                        + java.util.UUID.nameUUIDFromBytes(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                held.name = name;
                if (!query(connection, "SELECT GET_LOCK(?, 0)", name)) {
                    throw new IllegalStateException("电影同步范围已有任务执行：" + scope);
                }
                held.acquired = true;
                held.check();
                action.accept(held::check);
                held.check();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("电影同步锁连接失败", exception);
        }
    }

    /** 执行 MySQL 会话锁函数；返回 NULL 是服务端错误，不能当作普通未抢到锁。 */
    private static boolean query(Connection connection, String sql, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(LOCK_QUERY_TIMEOUT_SECONDS);
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("电影同步锁函数没有返回值");
                }
                int value = result.getInt(1);
                if (result.wasNull()) {
                    throw new SQLException("电影同步锁函数返回 NULL");
                }
                return value == 1;
            }
        }
    }

    /** 锁句柄用 try-with-resources 保留执行失败和释放失败的因果链。 */
    private static final class HeldLock implements AutoCloseable {
        /** 专用锁连接及其所属池，关闭句柄时总是淘汰该物理连接。 */
        private final Connection connection;
        private final HikariDataSource pool;
        /** 锁名及明确成功的获取标记；获取结果未知同样淘汰连接。 */
        private String name;
        private boolean acquired;
        /** 一旦失效不可恢复，禁止后续守卫误报重新有效。 */
        private MovieSyncAbortedException failure;

        private HeldLock(Connection connection, HikariDataSource pool) {
            this.connection = connection;
            this.pool = pool;
        }

        /** 长轮次保持连接活跃并确认锁所有权，失联时必须中断后续写入。 */
        void check() {
            if (failure != null) {
                throw failure;
            }
            if (Thread.currentThread().isInterrupted()) {
                failure = new MovieSyncAbortedException("电影同步已被中断");
                throw failure;
            }
            try {
                if (!query(connection, "SELECT IS_USED_LOCK(?) = CONNECTION_ID()", name)) {
                    failure = new MovieSyncAbortedException("电影同步锁所有权已丢失");
                    throw failure;
                }
            } catch (SQLException exception) {
                failure = new MovieSyncAbortedException("电影同步锁连接已失效", exception);
                throw failure;
            }
        }

        /** 显式释放后淘汰专用连接；释放或获取结果未知时绝不将可能持锁的连接归还池。 */
        @Override
        public void close() throws SQLException {
            SQLException releaseFailure = null;
            try {
                if (acquired && !query(connection, "SELECT RELEASE_LOCK(?)", name)) {
                    throw new SQLException("电影同步锁未由当前连接持有");
                }
            } catch (SQLException exception) {
                releaseFailure = exception;
            }
            try {
                pool.evictConnection(connection);
            } catch (RuntimeException evictionFailure) {
                // 淘汰异常不能掩盖释放失败；中止物理连接作为最后保护，所有失败均向上传播。
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException | RuntimeException abortFailure) {
                    evictionFailure.addSuppressed(abortFailure);
                }
                if (releaseFailure == null) {
                    throw evictionFailure;
                }
                releaseFailure.addSuppressed(evictionFailure);
            }
            if (releaseFailure != null) {
                throw releaseFailure;
            }
        }
    }
}
