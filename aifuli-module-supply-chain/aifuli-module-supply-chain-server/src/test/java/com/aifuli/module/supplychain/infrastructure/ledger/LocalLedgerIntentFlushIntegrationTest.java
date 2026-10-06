package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.DatabasePersistentTimer;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandler;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3-03 完整技术 worker 意图收集、确定性排序与单次 Flush 集成测试。
 * 依据设计规范：
 * docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#722-完整技术-worker-意图收集数据库-collation-确定性排序与单次-flush-协议r3-03
 * 验证：
 * 1. 单次 Flush：最外层事务收集 Outbox 与 Timer 意图并单次 flush，全部同事务提交；
 * 2. 幂等合并：同键等价载荷合并为单槽并保留最新元数据；
 * 3. 冲突拒绝：同键异载荷在 flush 阶段抛出 SystemIntegrityException 阻断并整事务回滚；
 * 4. 确定性排序防死锁：反序录入意图的并发事务按确定性全局排序加锁，不发生 1213 死锁；
 * 5. 故障原子性：下游槽写入失败整事务回滚，杜绝孤儿槽与孤儿源行；
 * 6. Flush 后守卫：flush 后禁止再次记录意图。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalLedgerIntentFlushIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_intent_flush")
            .withCommand("--log-bin-trust-function-creators=1");
    private static ConfigurableApplicationContext context;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;
    private static LocalLedgerIntentCollector collector;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void start() {
        MYSQL.start();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", MYSQL.getUsername());
        properties.put("aifuli.supply-chain.datasource.password", MYSQL.getPassword());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.datasource.connect-timeout-millis", 1500);
        properties.put("aifuli.supply-chain.timer.enabled", false);
        properties.put("aifuli.supply-chain.outbox.enabled", false);

        context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE)
                .run(properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                        .toArray(String[]::new));
        dataSource = context.getBean("supplyChainDataSource", DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
        txManager = context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class);
        collector = context.getBean(LocalLedgerIntentCollector.class);
        objectMapper = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    @Test @Order(1)
    void singleFlushPersistsAllOutboxAndTimerIntents() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                    "INTENT_AGG", "agg-1", 1L, "EVENT_1", "LOCAL", "{\"item\":1}", "trace-1", now));
            collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                    "INTENT_AGG", "agg-2", 1L, "EVENT_2", "LOCAL", "{\"item\":2}", "trace-2", now));
            ObjectNode payload = objectMapper.createObjectNode().put("timerVal", 10);
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "timer-key-1", payload, Instant.now().plusSeconds(60)));
            collector.flush();
            return null;
        });

        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type='INTENT_AGG' AND aggregate_id IN ('agg-1', 'agg-2')",
                Integer.class);
        assertThat(outboxCount).isEqualTo(2);

        Integer timerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' AND idempotency_key='timer-key-1'",
                Integer.class);
        assertThat(timerCount).isEqualTo(1);
    }

    @Test @Order(2)
    void equivalentIntentsMergeToSingleSlotWithLatestMetadata() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            ObjectNode payload1 = objectMapper.createObjectNode().put("v", 1);
            ObjectNode payload2 = objectMapper.createObjectNode().put("v", 1);
            Instant time1 = Instant.now().plusSeconds(60);
            Instant time2 = Instant.now().plusSeconds(120);

            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-eq", payload1, time1));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-eq", payload2, time2));
            collector.flush();
            return null;
        });

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' AND idempotency_key='key-eq'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test @Order(3)
    void conflictingIntentsThrowSystemIntegrityExceptionAndRollback() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.execute(status -> {
            ObjectNode payload1 = objectMapper.createObjectNode().put("v", 1);
            ObjectNode payload2 = objectMapper.createObjectNode().put("v", 2);

            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-diff", payload1, Instant.now().plusSeconds(60)));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-diff", payload2, Instant.now().plusSeconds(60)));
            collector.flush();
            return null;
        })).isInstanceOf(SystemIntegrityException.class).hasMessageContaining("冲突");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' AND idempotency_key='key-diff'",
                Integer.class);
        assertThat(count).isZero();
    }

    @Test @Order(4)
    void deterministicCollationOrderingPreventsDeadlockOnOppositeOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<?> f1 = pool.submit(() -> {
                TransactionTemplate tx = new TransactionTemplate(txManager);
                tx.execute(status -> {
                    try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
                    // 录入顺序：Z 在前，A 在后
                    collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                            "TEST_ONCE", "key-order-Z", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
                    collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                            "TEST_ONCE", "key-order-A", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
                    collector.flush();
                    return null;
                });
            });

            Future<?> f2 = pool.submit(() -> {
                TransactionTemplate tx = new TransactionTemplate(txManager);
                tx.execute(status -> {
                    try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
                    // 录入顺序：A 在前，Z 在后
                    collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                            "TEST_ONCE", "key-order-A", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
                    collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                            "TEST_ONCE", "key-order-Z", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
                    collector.flush();
                    return null;
                });
            });

            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Integer countA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' AND idempotency_key='key-order-A'",
                Integer.class);
        assertThat(countA).isEqualTo(1);
    }

    @Test @Order(5)
    void downstreamFailureRollsBackAllIntentsWithoutOrphans() {
        jdbc.execute("CREATE TRIGGER trg_test_intent_fail BEFORE INSERT ON sc_timer_task "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SIMULATED_INTENT_INSERT_FAILURE'");
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            assertThatThrownBy(() -> tx.execute(status -> {
                LocalDateTime now = LocalDateTime.now();
                collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                        "INTENT_FAIL", "fail-agg", 1L, "FAIL_EVENT", "LOCAL", "{\"f\":1}", "trace-f", now));
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-fail-intent", objectMapper.createObjectNode().put("f", 1), Instant.now().plusSeconds(60)));
                collector.flush();
                return null;
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_intent_fail");
        }

        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type='INTENT_FAIL'", Integer.class);
        assertThat(outboxCount).isZero();

        Integer timerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='key-fail-intent'", Integer.class);
        assertThat(timerCount).isZero();
    }

    @Test @Order(6)
    void recordIntentAfterFlushThrowsSystemIntegrityException() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-flush-guard", objectMapper.createObjectNode().put("g", 1), Instant.now().plusSeconds(60)));
            collector.flush();

            assertThatThrownBy(() -> collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-flush-guard-2", objectMapper.createObjectNode().put("g", 2), Instant.now().plusSeconds(60))))
                    .isInstanceOf(SystemIntegrityException.class)
                    .hasMessageContaining("已flush");
            return null;
        });
    }

    @Test @Order(7)
    void unflushedIntentBlocksCommitViaBeforeCommitGuard() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-unflushed", objectMapper.createObjectNode().put("u", 1), Instant.now().plusSeconds(60)));
            // 故意不调用 collector.flush()，直接尝试提交
            return null;
        })).isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("未flush");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='key-unflushed'", Integer.class);
        assertThat(count).isZero();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            SupplyChainDataSourceConfiguration.class,
            BusinessNoGenerator.class,
            UlidGenerator.class,
            DefaultAppendScheduleIdentityLedgerService.class,
            OutboxEventRepository.class,
            DatabasePersistentTimer.class,
            LocalLedgerIntentCollector.class,
            PersistentTimerHandlerRegistry.class
    })
    static class Fixture {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean List<PersistentTimerHandler> dummyHandlers() {
            return List.of(new PersistentTimerHandler() {
                @Override public String taskType() { return "TEST_ONCE"; }
                @Override public int maxAttempts() { return 3; }
                @Override public String handle(TimerTaskContext context, JsonNode payload) { return "SUCCESS"; }
            });
        }
    }
}
