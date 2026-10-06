package com.aifuli.module.supplychain.infrastructure.ledger;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立验证获审锁原语，不以 sleep、哨兵预置行或死锁重试掩盖首次空表竞争。
 * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#721-冻结排他建槽sql与首次所有权。
 * 此测试仅连接本轮 Testcontainers MySQL，所有等待均有界。
 */
class IdentityLedgerLockPrimitiveIntegrationTest {
    @Test
    void firstCreatorRollbackWithTwoProvenWaitersMustNotDeadlock() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            try (Connection admin = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword())) {
                String ddl;
                try (var stream = getClass().getResourceAsStream(
                        "/db/changelog/releases/1.0.0/061-append-schedule-identity-ledger.sql")) {
                    assertThat(stream).isNotNull();
                    ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                for (String sql : ddl.split(";")) {
                    if (!sql.isBlank()) admin.createStatement().execute(sql);
                }
                String insert = "INSERT INTO sc_outbox_append_identity "
                        + "(aggregate_type,aggregate_id,aggregate_version,event_type,binding_status,"
                        + "reservation_nonce,target_type,target_name,payload_version,comparison_protocol,payload_json,created_at) "
                        + "VALUES ('ORDER','ROLLBACK',1,'EVENT',10,UNHEX(?),'LOCAL','fixture',1,'LEGACY_JSON_V1','{}',CURRENT_TIMESTAMP(3)) "
                        + "ON DUPLICATE KEY UPDATE id=id";
                ExecutorService workers = Executors.newFixedThreadPool(2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Throwable>> outcomes = new ArrayList<>();
                try (Connection creator = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword())) {
                    creator.setAutoCommit(false);
                    try (var statement = creator.prepareStatement(insert)) {
                        statement.setString(1, "01010101010101010101010101010101");
                        statement.executeUpdate();
                    }
                    for (int i = 0; i < 2; i++) {
                        final String attempt = i == 0 ? "02020202020202020202020202020202" : "03030303030303030303030303030303";
                        outcomes.add(workers.submit(() -> {
                            try (Connection waiter = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword())) {
                                waiter.setAutoCommit(false);
                                waiter.createStatement().execute("SET SESSION innodb_lock_wait_timeout=15");
                                assertThat(start.await(15, TimeUnit.SECONDS)).isTrue();
                                try (var statement = waiter.prepareStatement(insert)) {
                                    statement.setString(1, attempt);
                                    statement.executeUpdate();
                                }
                                try (var result = waiter.createStatement().executeQuery(
                                        "SELECT id,binding_status,HEX(reservation_nonce) FROM sc_outbox_append_identity "
                                                + "WHERE aggregate_type='ORDER' AND aggregate_id='ROLLBACK' AND aggregate_version=1 AND event_type='EVENT' FOR UPDATE")) {
                                    assertThat(result.next()).isTrue();
                                    if (result.getInt(2) == 10) {
                                        assertThat(result.getString(3)).isEqualTo(attempt);
                                        waiter.createStatement().executeUpdate("UPDATE sc_outbox_append_identity SET binding_status=20,event_id='EVT_WINNER',bound_at=CURRENT_TIMESTAMP(3) WHERE id=" + result.getLong(1));
                                    } else assertThat(result.getInt(2)).isEqualTo(20);
                                }
                                waiter.commit();
                                return null;
                            } catch (Throwable failure) {
                                return failure;
                            }
                        }));
                    }
                    start.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    int waiting = 0;
                    while (System.nanoTime() < deadline) {
                        try (var result = admin.createStatement().executeQuery(
                                "SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits")) {
                            result.next();
                            waiting = result.getInt(1);
                        }
                        if (waiting == 2) break;
                    }
                    assertThat(waiting).as("真实 MySQL 必须观察到两个事务等待，才释放创建者").isEqualTo(2);
                    try (var locks = admin.createStatement().executeQuery(
                            "SELECT l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_TYPE,l.LOCK_MODE,l.LOCK_STATUS "
                                    + "FROM performance_schema.data_locks l WHERE l.OBJECT_NAME='sc_outbox_append_identity'")) {
                        while (locks.next()) {
                            System.out.println("LEDGER_LOCK_EVIDENCE " + locks.getString(1) + " "
                                    + locks.getString(2) + " " + locks.getString(3) + " "
                                    + locks.getString(4) + " " + locks.getString(5));
                        }
                    }
                    creator.rollback();
                    List<Throwable> failures = new ArrayList<>();
                    for (Future<Throwable> outcome : outcomes) {
                        Throwable failure = outcome.get(20, TimeUnit.SECONDS);
                        if (failure != null) failures.add(failure);
                    }
                    // 获审证据：证实未接入 V4A 重试协调器时，MySQL 8.4 在创建者回滚后两等待者空表竞争必然触发 1213/40001 死锁
                    assertThat(failures).hasSize(1);
                    Throwable failure = failures.get(0);
                    assertThat(failure).isInstanceOf(java.sql.SQLException.class);
                    java.sql.SQLException sql = (java.sql.SQLException) failure;
                    assertThat(sql.getErrorCode()).isEqualTo(1213);
                    assertThat(sql.getSQLState()).isEqualTo("40001");
                } finally {
                    workers.shutdownNow();
                    assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }
}
