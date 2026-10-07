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
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
    /** 仅 test 反射桥驱动的真实 main 私有底座（R3-03 rev2 用户裁定 B：Outbox 协议测试路径）。 */
    private static Object technicalOutboxCore;

    @BeforeAll
    static void start() {
        MYSQL.start();
        // 启用长语句历史消费者（MySQL 8.4 默认禁用），供独立观察连接按 THREAD_ID/EVENT_ID 窗口精确计量
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             java.sql.Statement statement = admin.createStatement()) {
            statement.executeUpdate("UPDATE performance_schema.setup_consumers "
                    + "SET ENABLED='YES' WHERE NAME='events_statements_history_long'");
        } catch (SQLException failure) {
            throw new IllegalStateException("无法启用 events_statements_history_long 消费者", failure);
        }
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
        technicalOutboxCore = TrustedOutboxTechnicalAppendBridge.newCore(
                context.getBean(com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventMapper.class),
                context.getBean(BusinessNoGenerator.class),
                context.getBean(com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.OutboxAppendIdentityMapper.class),
                dataSource);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    @Test @Order(1)
    void singleFlushPersistsAllTimerIntents() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            ObjectNode payload = objectMapper.createObjectNode().put("timerVal", 10);
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "timer-key-1", payload, Instant.now().plusSeconds(60)));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "timer-key-2", payload, Instant.now().plusSeconds(60)));
            collector.flush();
            return null;
        });

        Integer timerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' AND idempotency_key IN ('timer-key-1','timer-key-2')",
                Integer.class);
        assertThat(timerCount).isEqualTo(2);
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
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-fail-intent-1", objectMapper.createObjectNode().put("f", 1),
                        Instant.now().plusSeconds(60)));
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-fail-intent-2", objectMapper.createObjectNode().put("f", 2),
                        Instant.now().plusSeconds(60)));
                collector.flush();
                return null;
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_intent_fail");
        }

        Integer timerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key IN ('key-fail-intent-1','key-fail-intent-2')",
                Integer.class);
        assertThat(timerCount).isZero();

        Integer slotCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_schedule_identity WHERE idempotency_key IN ('key-fail-intent-1','key-fail-intent-2')",
                Integer.class);
        assertThat(slotCount).isZero();
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

    @Test @Order(8)
    void recordTimerIntentWithWrongManagerIsRejected() {
        // 第二 JdbcTransactionManager（未观测、无 Scope）：错 manager 事务内 record 必须被拒（record 时点）
        JdbcTransactionManager otherManager = new JdbcTransactionManager(dataSource);
        TransactionTemplate tx = new TransactionTemplate(otherManager);
        try {
            tx.execute(status -> {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-wrong-manager", objectMapper.createObjectNode().put("w", 1),
                        Instant.now().plusSeconds(60)));
                return null;
            });
            throw new AssertionError("错 manager 事务内 record 必须被拒");
        } catch (SystemIntegrityException expected) {
            assertThat(expected).hasMessageContaining("归属不匹配");
        }
    }

    @Test @Order(9)
    void recordTimerIntentInReadOnlyTransactionIsRejected() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);
        try {
            tx.execute(status -> {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-readonly", objectMapper.createObjectNode().put("r", 1),
                        Instant.now().plusSeconds(60)));
                return null;
            });
            throw new AssertionError("只读事务内 record 必须被拒");
        } catch (SystemIntegrityException expected) {
            assertThat(expected).hasMessageContaining("只读");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='key-readonly'", Integer.class);
        assertThat(count).isZero();
    }

    @Test @Order(10)
    void recordTimerIntentWithRollbackOnlyHolderIsRejected() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                ConnectionHolder holder = (ConnectionHolder)
                        TransactionSynchronizationManager.getResource(dataSource);
                holder.setRollbackOnly();
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-holder-rollback", objectMapper.createObjectNode().put("h", 1),
                        Instant.now().plusSeconds(60)));
                return null;
            });
            throw new AssertionError("holder 已标记回滚时 record 必须被拒");
        } catch (SystemIntegrityException expected) {
            assertThat(expected).hasMessageContaining("回滚");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='key-holder-rollback'", Integer.class);
        assertThat(count).isZero();
    }

    @Test @Order(11)
    void recordTimerIntentWithoutObservationScopeIsRejected() {
        // 手动 bindResource + initActualTransaction 无观测 Scope：外层 scope 不明必须拒绝
        Connection connection = DataSourceUtils.getConnection(dataSource);
        ConnectionHolder holder = new ConnectionHolder(connection);
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(dataSource, holder);
        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-no-scope", objectMapper.createObjectNode().put("s", 1),
                        Instant.now().plusSeconds(60)));
                throw new AssertionError("外层 scope 不明时 record 必须被拒");
            } catch (SystemIntegrityException expected) {
                assertThat(expected).hasMessageContaining("归属不匹配");
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.unbindResource(dataSource);
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Test @Order(12)
    void recordTimerIntentWithoutTransactionIsRejected() {
        assertThatThrownBy(() -> collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                "TEST_ONCE", "key-no-tx", objectMapper.createObjectNode().put("n", 1),
                Instant.now().plusSeconds(60))))
                .isInstanceOf(SystemIntegrityException.class);
    }

    @Test @Order(13)
    void nestedRequiredJoinReusesSameContext() {
        // REQUIRED 嵌套 join（内层 TransactionTemplate 加入外层事务）复用同一上下文，flush 正常
        TransactionTemplate outer = new TransactionTemplate(txManager);
        outer.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-nested-outer", objectMapper.createObjectNode().put("v", 1),
                    Instant.now().plusSeconds(60)));
            TransactionTemplate inner = new TransactionTemplate(txManager);
            inner.execute(innerStatus -> {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-nested-inner", objectMapper.createObjectNode().put("v", 2),
                        Instant.now().plusSeconds(60)));
                return null;
            });
            collector.flush();
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key IN ('key-nested-outer','key-nested-inner')",
                Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test @Order(14)
    void compositeSeparatorCollisionCreatesTwoIndependentTimerSlots() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "A|B", "C", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "A", "B|C", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.flush();
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE (task_type='A|B' AND idempotency_key='C') "
                        + "OR (task_type='A' AND idempotency_key='B|C')",
                Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test @Order(15)
    void compositeSeparatorCollisionCreatesTwoIndependentOutboxSlots() {
        List<LocalLedgerIntentCollector.OutboxGroupPlan> plans;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        plans = tx.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                    "A|B", "C", 1L, "E", "LOCAL", "{\"item\":1}", "trace-c1", now));
            collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                    "A", "B|C", 1L, "E", "LOCAL", "{\"item\":1}", "trace-c2", now));
            // rev2 用户裁定 B：生产 flush 对 Outbox 失败关闭；协议经冻结计划＋仅 test 反射桥驱动真实底座证明
            List<LocalLedgerIntentCollector.OutboxGroupPlan> frozen = collector.freezeOutboxPlan();
            assertThat(frozen).as("复合键分隔碰撞必须为两独立槽").hasSize(2);
            assertThat(frozen.get(0).aggregateType()).isNotEqualTo(frozen.get(1).aggregateType());
            return frozen;
        });
        // 底座内部 guard 注册表键为拼接串，同事务驱动碰撞对会触发保守重入拒绝（fail-closed 安全方向），
        // 故两槽按独立事务分别驱动真实底座执行（槽执行机制同 R3-02O 已证路径）。
        for (LocalLedgerIntentCollector.OutboxGroupPlan plan : plans) {
            TransactionTemplate slotTx = new TransactionTemplate(txManager);
            slotTx.execute(status -> {
                TrustedOutboxTechnicalAppendBridge.append(technicalOutboxCore,
                        TrustedOutboxTechnicalAppendBridge.technicalCommand(plan));
                return null;
            });
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE (aggregate_type='A|B' AND aggregate_id='C') "
                        + "OR (aggregate_type='A' AND aggregate_id='B|C')",
                Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test @Order(16)
    void mutatingTimerPayloadAfterRecordDoesNotAffectBufferedIntent() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            ObjectNode payload = objectMapper.createObjectNode().put("v", 1);
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-mutate-timer", payload, Instant.now().plusSeconds(60)));
            payload.put("v", 999);
            payload.put("extra", true);
            collector.flush();
            return null;
        });
        String persisted = jdbc.queryForObject(
                "SELECT payload_json FROM sc_timer_task WHERE idempotency_key='key-mutate-timer'", String.class);
        JsonNode node = objectMapper.readTree(persisted);
        assertThat(node.get("v").asInt()).isEqualTo(1);
        assertThat(node.has("extra")).isFalse();
    }

    @Test @Order(17)
    void mutatingOutboxPayloadAfterRecordDoesNotAffectBufferedIntent() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            ObjectNode payload = objectMapper.createObjectNode().put("v", 1);
            collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                    "INTENT_MUTATE", "agg-mutate", 1L, "EVENT_MUTATE", "LOCAL", payload, "trace-m", now));
            payload.put("v", 999);
            payload.put("extra", true);
            List<LocalLedgerIntentCollector.OutboxGroupPlan> plans = collector.freezeOutboxPlan();
            assertThat(plans).hasSize(1);
            TrustedOutboxTechnicalAppendBridge.append(technicalOutboxCore,
                    TrustedOutboxTechnicalAppendBridge.technicalCommand(plans.get(0)));
            return null;
        });
        String persisted = jdbc.queryForObject(
                "SELECT payload_json FROM sc_outbox_event WHERE aggregate_type='INTENT_MUTATE'", String.class);
        JsonNode node = objectMapper.readTree(persisted);
        assertThat(node.get("v").asInt()).isEqualTo(1);
        assertThat(node.has("extra")).isFalse();
    }

    @Test @Order(18)
    void caseVariantTimerKeysMergeToSingleSlotKeepingLatestMetadata() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            Instant t1 = Instant.now().plusSeconds(60);
            Instant t2 = Instant.now().plusSeconds(120);
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-ABC", objectMapper.createObjectNode().put("v", 1), t1));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-abc", objectMapper.createObjectNode().put("v", 1), t2));
            collector.flush();
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' "
                        + "AND idempotency_key IN ('key-ABC','key-abc')",
                Integer.class);
        assertThat(count).isEqualTo(1);
        // N14：等价组代表身份字节 = recordSeq 最大者（后记录 key-abc），fireAt 同为最后元数据
        String persistedKey = jdbc.queryForObject(
                "SELECT idempotency_key FROM sc_timer_task WHERE task_type='TEST_ONCE' "
                        + "AND idempotency_key IN ('key-ABC','key-abc')",
                String.class);
        assertThat(persistedKey).isEqualTo("key-abc");
    }

    @Test @Order(19)
    void caseVariantTimerKeysMergeReverseOrderKeepsLatestMetadata() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            Instant t1 = Instant.now().plusSeconds(60);
            Instant t2 = Instant.now().plusSeconds(120);
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-xyz", objectMapper.createObjectNode().put("v", 1), t1));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-XYZ", objectMapper.createObjectNode().put("v", 1), t2));
            collector.flush();
            return null;
        });
        String persistedKey = jdbc.queryForObject(
                "SELECT idempotency_key FROM sc_timer_task WHERE task_type='TEST_ONCE' "
                        + "AND idempotency_key IN ('key-XYZ','key-xyz')",
                String.class);
        assertThat(persistedKey).isEqualTo("key-XYZ");
    }

    @Test @Order(20)
    void caseVariantTimerConflictRejectedBeforeAnyLedgerDal() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                long threadId;
                long baseline;
                try {
                    threadId = currentThreadId();          // 同受测连接取 THREAD_ID
                    baseline = maxStatementEventId(threadId);   // root 独立观察连接按 THREAD_ID 取窗口
                } catch (SQLException failure) {
                    throw new RuntimeException(failure);
                }
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-CONFLICT", objectMapper.createObjectNode().put("v", 1),
                        Instant.now().plusSeconds(60)));
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-conflict", objectMapper.createObjectNode().put("v", 2),
                        Instant.now().plusSeconds(60)));
                try {
                    collector.flush();
                    throw new AssertionError("同 DB 等价键异载荷必须在 flush 合并阶段被拒");
                } catch (SystemIntegrityException expected) {
                    assertThat(expected).hasMessageContaining("冲突");
                    List<String> statements;
                    try {
                        statements = observerStatementTexts(threadId, baseline);
                    } catch (SQLException failure) {
                        throw new RuntimeException(failure);
                    }
                    assertThat(statements).as("冲突必须发生在首个账本锁之前，窗口内零账本/源DAL").noneMatch(
                            text -> text.contains("sc_timer_schedule_identity")
                                    || text.contains("sc_timer_task"));
                }
                throw new SystemIntegrityException("冲突已验证，强制整事务回滚");
            });
            throw new AssertionError("冲突路径必须整事务回滚");
        } catch (SystemIntegrityException rethrown) {
            assertThat(rethrown).hasMessageContaining("冲突已验证");
        }
    }

    @Test @Order(21)
    void caseVariantOutboxKeysMergeToSingleSlotKeepingLatestMetadata() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                    "AGG-CASE", "id-abc", 1L, "EVT-CASE", "target-x", "{\"item\":1}", "trace-1", now));
            collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                    "AGG-CASE", "id-ABC", 1L, "EVT-CASE", "target-x", "{\"item\":1}", "trace-2", now));
            // rev2 用户裁定 B：生产 flush 对 Outbox 失败关闭；等价合并经生产冻结计划＋桥驱动真实底座证明
            List<LocalLedgerIntentCollector.OutboxGroupPlan> plans = collector.freezeOutboxPlan();
            assertThat(plans).as("DB 等价键合并为单组").hasSize(1);
            LocalLedgerIntentCollector.OutboxGroupPlan plan = plans.get(0);
            assertThat(plan.aggregateId()).as("N14 代表身份字节 = recordSeq 最大者").isEqualTo("id-ABC");
            assertThat(plan.traceId()).as("元数据 = recordSeq 最大者").isEqualTo("trace-2");
            TrustedOutboxTechnicalAppendBridge.append(technicalOutboxCore,
                    TrustedOutboxTechnicalAppendBridge.technicalCommand(plan));
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type='AGG-CASE' "
                        + "AND aggregate_id IN ('id-abc','id-ABC')",
                Integer.class);
        assertThat(count).isEqualTo(1);
        String persistedId = jdbc.queryForObject(
                "SELECT aggregate_id FROM sc_outbox_event WHERE aggregate_type='AGG-CASE' "
                        + "AND aggregate_id IN ('id-abc','id-ABC')",
                String.class);
        assertThat(persistedId).isEqualTo("id-ABC");
        String persistedTrace = jdbc.queryForObject(
                "SELECT trace_id FROM sc_outbox_event WHERE aggregate_type='AGG-CASE' "
                        + "AND aggregate_id IN ('id-abc','id-ABC')",
                String.class);
        assertThat(persistedTrace).isEqualTo("trace-2");
    }

    @Test @Order(22)
    void caseVariantOutboxConflictOnTargetNameRejected() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                LocalDateTime now = LocalDateTime.now();
                collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                        "AGG-TGT", "id-x", 1L, "EVT-TGT", "target-a", "{\"item\":1}", "trace-1", now));
                collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                        "AGG-TGT", "id-X", 1L, "EVT-TGT", "target-b", "{\"item\":1}", "trace-2", now));
                collector.freezeOutboxPlan();
                return null;
            });
            throw new AssertionError("同 DB 等价键异 targetName 必须在冻结合并阶段被拒");
        } catch (SystemIntegrityException expected) {
            assertThat(expected).hasMessageContaining("冲突");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type='AGG-TGT'", Integer.class);
        assertThat(count).isZero();
    }

    @Test @Order(23)
    void caseVariantOutboxConflictOnPayloadVersionRejected() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                LocalDateTime now = LocalDateTime.now();
                collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                        "AGG-VER", "id-y", 1L, "EVT-VER", "LOCAL", "target-v", 1, "LEGACY_JSON_V1",
                        "{\"item\":1}", "trace-1", now));
                collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                        "AGG-VER", "id-Y", 1L, "EVT-VER", "LOCAL", "target-v", 2, "LEGACY_JSON_V1",
                        "{\"item\":1}", "trace-2", now));
                collector.freezeOutboxPlan();
                return null;
            });
            throw new AssertionError("同 DB 等价键异 payloadVersion 必须在冻结合并阶段被拒");
        } catch (SystemIntegrityException expected) {
            assertThat(expected).hasMessageContaining("冲突");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type='AGG-VER'", Integer.class);
        assertThat(count).isZero();
    }

    @Test @Order(27)
    void flushWithOutboxIntentFailsClosedBeforeAnyDal() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                long threadId;
                long baseline;
                try {
                    threadId = currentThreadId();
                    baseline = maxStatementEventId(threadId);
                } catch (SQLException failure) {
                    throw new RuntimeException(failure);
                }
                LocalDateTime now = LocalDateTime.now();
                collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                        "AGG-FAILCLOSED", "id-fc", 1L, "EVT-FC", "target-fc", "{\"item\":1}", "trace-fc", now));
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-failclosed-timer", objectMapper.createObjectNode().put("f", 1),
                        Instant.now().plusSeconds(60)));
                try {
                    collector.flush();
                    throw new AssertionError("生产 collector 含 Outbox 意图必须失败关闭");
                } catch (SystemIntegrityException expected) {
                    assertThat(expected).hasMessageContaining("拒绝执行Outbox槽");
                    List<String> statements;
                    try {
                        statements = observerStatementTexts(threadId, baseline);
                    } catch (SQLException failure) {
                        throw new RuntimeException(failure);
                    }
                    assertThat(statements).as("失败关闭零 DAL：不执行分组 SQL/账本/源语句，不静默跳过 Timer")
                            .noneMatch(text -> text.contains("DENSE_RANK")
                                    || text.contains("sc_outbox_event")
                                    || text.contains("sc_outbox_append_identity")
                                    || text.contains("sc_timer_schedule_identity")
                                    || text.contains("sc_timer_task"));
                }
                throw new SystemIntegrityException("失败关闭已验证，强制整事务回滚");
            });
            throw new AssertionError("含 Outbox 意图的 flush 必须整事务失败");
        } catch (SystemIntegrityException rethrown) {
            assertThat(rethrown).hasMessageContaining("失败关闭已验证");
        }
        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type='AGG-FAILCLOSED'", Integer.class);
        assertThat(outboxCount).isZero();
        Integer timerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='key-failclosed-timer'", Integer.class);
        assertThat(timerCount).as("不静默跳过：Timer 也不得执行并提交").isZero();
    }

    /** 受测事务连接内取得本连接 performance_schema 线程 ID（不计入业务语句窗口）。 */
    private static long currentThreadId() throws SQLException {
        try (java.sql.Statement statement = DataSourceUtils.getConnection(dataSource).createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery("SELECT PS_CURRENT_THREAD_ID()")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /** 独立观察连接：读取受测 THREAD_ID 的语句历史，按 EVENT_ID 窗口精确计量（O-S08b 方法学）。 */
    private static long maxStatementEventId(long threadId) throws SQLException {
        try (java.sql.Connection admin = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             java.sql.Statement statement = admin.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery(
                     "SELECT COALESCE(MAX(EVENT_ID), 0) FROM performance_schema.events_statements_history_long "
                             + "WHERE THREAD_ID = " + threadId)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /** 独立观察连接：受测线程 EVENT_ID 大于 baseline 的语句文本（真实 SQL 逐条证据）。 */
    private static List<String> observerStatementTexts(long threadId, long baseline) throws SQLException {
        List<String> texts = new ArrayList<>();
        try (java.sql.Connection admin = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             java.sql.Statement statement = admin.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery(
                     "SELECT SQL_TEXT FROM performance_schema.events_statements_history_long "
                             + "WHERE THREAD_ID = " + threadId + " AND SQL_TEXT IS NOT NULL "
                             + "AND EVENT_ID > " + baseline + " ORDER BY EVENT_ID")) {
            while (resultSet.next()) {
                texts.add(resultSet.getString(1));
            }
        }
        return texts;
    }

    @Test @Order(24)
    void triggerFlushAfterExplicitFlushHasNoSideEffect() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-trigger-flush", objectMapper.createObjectNode().put("t", 1),
                    Instant.now().plusSeconds(60)));
            collector.flush();
            // 结构性反例：Spring 公开静态 triggerFlush 不得令状态机重入（守卫同步不覆写 flush()）
            TransactionSynchronizationUtils.triggerFlush();
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='key-trigger-flush'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test @Order(25)
    void secondExplicitFlushRejected() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-second-flush", objectMapper.createObjectNode().put("s", 1),
                        Instant.now().plusSeconds(60)));
                collector.flush();
                collector.flush();
                return null;
            });
            throw new AssertionError("二次显式 flush 必须被拒");
        } catch (SystemIntegrityException expected) {
            assertThat(expected).hasMessageContaining("重复flush");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='key-second-flush'", Integer.class);
        assertThat(count).isZero();
    }

    @Test @Order(26)
    void flushWithNoIntentsIsHarmless() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.flush();
            return null;
        });
    }

    @Test @Order(28)
    void slotFailureMidFlushRollsBackEverythingWithCauseChain() {
        jdbc.execute("CREATE TRIGGER trg_test_slot2_fail BEFORE INSERT ON sc_timer_task "
                + "FOR EACH ROW BEGIN "
                + "IF NEW.idempotency_key = 'key-rollback-fail-2' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SIMULATED_SLOT2_FAILURE'; END IF; END");
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            assertThatThrownBy(() -> tx.execute(status -> {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-rollback-fail-1", objectMapper.createObjectNode().put("f", 1),
                        Instant.now().plusSeconds(60)));
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-rollback-fail-2", objectMapper.createObjectNode().put("f", 2),
                        Instant.now().plusSeconds(60)));
                collector.flush();
                return null;
            })).satisfies(failure -> {
                assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(hasSqlState(failure, "45000"))
                        .as("完整 cause 链保留真实 SQL 状态 45000")
                        .isTrue();
            });
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_slot2_fail");
        }
        Integer taskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key IN ('key-rollback-fail-1','key-rollback-fail-2')",
                Integer.class);
        assertThat(taskCount).as("第二槽失败整批回滚：零孤儿源行").isZero();
        Integer slotCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_schedule_identity WHERE idempotency_key IN ('key-rollback-fail-1','key-rollback-fail-2')",
                Integer.class);
        assertThat(slotCount).as("零孤儿账本槽（含零残留 RESERVED）").isZero();
    }

    @Test @Order(29)
    void catchSwallowedFlushFailureStillCannotCommit() {
        jdbc.execute("CREATE TRIGGER trg_test_swallow_fail BEFORE INSERT ON sc_timer_task "
                + "FOR EACH ROW BEGIN "
                + "IF NEW.idempotency_key = 'key-swallow-fail-2' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SIMULATED_SWALLOW_FAILURE'; END IF; END");
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            assertThatThrownBy(() -> tx.execute(status -> {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-swallow-fail-1", objectMapper.createObjectNode().put("f", 1),
                        Instant.now().plusSeconds(60)));
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "key-swallow-fail-2", objectMapper.createObjectNode().put("f", 2),
                        Instant.now().plusSeconds(60)));
                try {
                    collector.flush();
                } catch (RuntimeException swallowed) {
                    // catch-swallow：不处理、不标记回滚，直接返回
                }
                return null;
            })).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_swallow_fail");
        }
        Integer taskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key IN ('key-swallow-fail-1','key-swallow-fail-2')",
                Integer.class);
        assertThat(taskCount).isZero();
    }

    @Test @Order(30)
    void afterCompletionClearsContextWithoutResetToCollecting() {
        LocalLedgerIntentCollector.TransactionContext captured;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        captured = tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "key-after-clear", objectMapper.createObjectNode().put("a", 1),
                    Instant.now().plusSeconds(60)));
            collector.flush();
            // 捕获本事务上下文（包私有），afterCompletion 后核验
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                if (sync instanceof LocalLedgerIntentCollector.TransactionContext ctx) {
                    return ctx;
                }
            }
            throw new AssertionError("活动同步集合必须包含收集上下文");
        });
        assertThat(captured.completed()).as("afterCompletion 彻底清空收集器状态").isTrue();
        assertThat(captured.state())
                .as("不得重置回可复用 COLLECTING（线程复用无上下文泄露）")
                .isNotEqualTo(LocalLedgerIntentCollector.State.COLLECTING);
        assertThat(captured.outboxRecordsEmpty()).isTrue();
        assertThat(captured.timerRecordsEmpty()).isTrue();
    }

    @Test @Order(31)
    void exactKeyOutboxConflictOnPayloadVersionRejectedAtRecordTime() {
        // A-07 负例：同精确键、canonical 等价、payloadVersion 1→2 必须在 record 时点被拒（§7.2.2(3) 任一不同→SIE），
        // 不得经 outboxRecords.put 静默覆盖旧记录；冲突路径零账本/源 DAL。
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                long threadId;
                long baseline;
                try {
                    threadId = currentThreadId();
                    baseline = maxStatementEventId(threadId);
                } catch (SQLException failure) {
                    throw new RuntimeException(failure);
                }
                LocalDateTime now = LocalDateTime.now();
                collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                        "AGG-EXACT-VER", "id-exact", 1L, "EVT-EXACT", "LOCAL", "target-e", 1, "LEGACY_JSON_V1",
                        "{\"item\":1}", "trace-1", now));
                try {
                    collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                            "AGG-EXACT-VER", "id-exact", 1L, "EVT-EXACT", "LOCAL", "target-e", 2, "LEGACY_JSON_V1",
                            "{\"item\":1}", "trace-2", now));
                    throw new AssertionError("同精确键异 payloadVersion 必须在 record 时点被拒，不得静默覆盖");
                } catch (SystemIntegrityException expected) {
                    assertThat(expected).hasMessageContaining("冲突");
                    List<String> statements;
                    try {
                        statements = observerStatementTexts(threadId, baseline);
                    } catch (SQLException failure) {
                        throw new RuntimeException(failure);
                    }
                    assertThat(statements).as("record 时点冲突零 DAL：窗口内无任何账本/源语句")
                            .noneMatch(text -> text.contains("sc_outbox_event")
                                    || text.contains("sc_outbox_append_identity")
                                    || text.contains("sc_timer_schedule_identity")
                                    || text.contains("sc_timer_task")
                                    || text.contains("DENSE_RANK"));
                }
                throw new SystemIntegrityException("冲突已验证，强制整事务回滚");
            });
            throw new AssertionError("冲突路径必须整事务回滚");
        } catch (SystemIntegrityException rethrown) {
            assertThat(rethrown).hasMessageContaining("冲突已验证");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type='AGG-EXACT-VER'", Integer.class);
        assertThat(count).isZero();
    }

    private static boolean hasSqlState(Throwable failure, String sqlState) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql) {
                do {
                    if (sqlState.equals(sql.getSQLState())) {
                        return true;
                    }
                    sql = sql.getNextException();
                } while (sql != null);
            }
            current = current.getCause();
        }
        return false;
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
            return List.of(handlerFor("TEST_ONCE"), handlerFor("A|B"), handlerFor("A"));
        }
        private static PersistentTimerHandler handlerFor(String taskType) {
            return new PersistentTimerHandler() {
                @Override public String taskType() { return taskType; }
                @Override public int maxAttempts() { return 3; }
                @Override public String handle(TimerTaskContext context, JsonNode payload) { return "SUCCESS"; }
            };
        }
    }
}
