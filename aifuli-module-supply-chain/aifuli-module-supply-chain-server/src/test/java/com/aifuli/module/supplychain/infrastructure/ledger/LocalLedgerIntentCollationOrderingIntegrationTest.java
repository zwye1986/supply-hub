package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.CollationOrderingMapper;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.timer.DatabasePersistentTimer;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandler;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3-03 §7.2.2(4) 冻结分组排序 SQL 的真实 MySQL 运行义务（C-ORDER）：
 * 每 flush 每类恰 1 条无实体表 SQL（独立观察连接按 THREAD_ID/EVENT_ID 窗口）、
 * data_locks 零增量、不锚定 RR 读视图（O-S08c 式）、collation 变体逐项固定、
 * N17 k*_orig 定位 miss 失败关闭、N18 连接 utf8mb4 断言与原始字节回传。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalLedgerIntentCollationOrderingIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_collation_order")
            .withCommand("--log-bin-trust-function-creators=1");
    private static ConfigurableApplicationContext context;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;
    private static LocalLedgerIntentCollector collector;
    private static ObjectMapper objectMapper;
    private static CollationOrderingMapper collationOrderingMapper;

    @BeforeAll
    static void start() {
        MYSQL.start();
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
        collationOrderingMapper = context.getBean(CollationOrderingMapper.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    @Test @Order(1)
    void timerProductionFlushExecutesExactlyOneGroupingSqlPerFlush() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            long threadId = currentThreadId();
            long baseline = maxStatementEventId(threadId);
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "count-a", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "count-b", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "count-c", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.flush();
            List<String> window = observerStatementTexts(threadId, baseline);
            List<String> selects = window.stream()
                    .filter(text -> text.trim().toUpperCase(Locale.ROOT).startsWith("SELECT"))
                    .toList();
            long groupingCount = selects.stream().filter(text -> text.contains("DENSE_RANK")).count();
            assertThat(groupingCount).as("每 flush 每类分组 SQL 恰 1 条").isEqualTo(1);
            // A-12：其它 SELECT 仅允许账本当前读、驱动 probe 与 R3-02T 冻结路径的数据库时钟读（digest 匹配大小写不敏感）
            selects.forEach(text -> {
                String upper = text.toUpperCase(Locale.ROOT);
                assertThat(upper).satisfiesAnyOf(
                        candidate -> assertThat(candidate).contains("DENSE_RANK"),
                        candidate -> assertThat(candidate).contains("SC_TIMER_SCHEDULE_IDENTITY"),
                        candidate -> assertThat(candidate).contains("@@SESSION.TRANSACTION_READ_ONLY"),
                        candidate -> assertThat(candidate).contains("CURRENT_TIMESTAMP(3)"));
            });
            return null;
        });
    }

    @Test @Order(2)
    void outboxFreezeExecutesExactlyOneGroupingSqlPerFreeze() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            long threadId = currentThreadId();
            long baseline = maxStatementEventId(threadId);
            collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                    "COUNT-OBX", "obx-a", 1L, "EVT-COUNT", "target-count", "{\"item\":1}", "trace-1", java.time.LocalDateTime.now()));
            collector.recordOutboxIntent(new LocalLedgerIntentCollector.OutboxIntent(
                    "COUNT-OBX", "obx-b", 1L, "EVT-COUNT", "target-count", "{\"item\":1}", "trace-2", java.time.LocalDateTime.now()));
            List<LocalLedgerIntentCollector.OutboxGroupPlan> plans = collector.freezeOutboxPlan();
            assertThat(plans).hasSize(2);
            List<String> window = observerStatementTexts(threadId, baseline);
            long groupingCount = window.stream()
                    .filter(text -> text.trim().toUpperCase(Locale.ROOT).startsWith("SELECT"))
                    .filter(text -> text.contains("DENSE_RANK"))
                    .count();
            assertThat(groupingCount).as("Outbox 冻结每次恰 1 条分组 SQL").isEqualTo(1);
            assertThat(window).noneMatch(text -> text.contains("sc_outbox_append_identity")
                    || text.contains("sc_outbox_event"));
            return null;
        });
    }

    @Test @Order(3)
    void groupingSqlAcquiresZeroDataLocks() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            long threadId = currentThreadId();
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "locks-a", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "locks-b", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.freezeTimerPlan();
            Integer lockCount = dataLocksForThread(threadId);
            assertThat(lockCount).as("分组 SQL 零 InnoDB 访问 → data_locks 零增量").isZero();
            return null;
        });
    }

    @Test @Order(4)
    void groupingSqlDoesNotAnchorReadView() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "rr-a", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.freezeTimerPlan();
            insertMarkerTask("marker-rr-visible");
            Integer visible = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='marker-rr-visible'", Integer.class);
            assertThat(visible).as("无表分组 SQL 不锚定 RR 读视图，随后表读必须可见新提交行").isEqualTo(1);
            return null;
        });
    }

    @Test @Order(5)
    void firstTableReadAnchorsReadViewControl() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            Integer before = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='marker-rr-control'", Integer.class);
            assertThat(before).isZero();
            insertMarkerTask("marker-rr-control");
            Integer after = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='marker-rr-control'", Integer.class);
            assertThat(after).as("对照：表读已锚定 RR 视图，新提交行不可见").isZero();
            return null;
        });
    }

    @Test @Order(6)
    void precomposedAndCombiningAccentKeysMerge() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "caf\u00E9", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "cafe\u0301", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.flush();
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' AND idempotency_key='cafe\u0301'",
                Integer.class);
        assertThat(count).as("预组合 é 与 e+U+0301 组合附标为 DB 等价键，合并为单槽").isEqualTo(1);
        String persisted = jdbc.queryForObject(
                "SELECT idempotency_key FROM sc_timer_task WHERE task_type='TEST_ONCE' "
                        + "AND (idempotency_key='caf\u00E9' OR idempotency_key='cafe\u0301')",
                String.class);
        assertThat(persisted).as("代表身份字节 = recordSeq 最大者（后记录的 cafe+U+0301）").isEqualTo("cafe\u0301");
    }

    @Test @Order(7)
    void zeroWeightCombiningGraphemeJoinerKeyMerges() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "abc", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "a\u034Fbc", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.flush();
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' "
                        + "AND (idempotency_key='abc' OR idempotency_key='a\u034Fbc')",
                Integer.class);
        assertThat(count).as("U+034F 组合字素连接符零权重 → DB 等价合并").isEqualTo(1);
    }

    @Test @Order(8)
    void accentCaseVariantKeysMerge() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "cafe", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "CAF\u00C9", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
            collector.flush();
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' "
                        + "AND (idempotency_key='cafe' OR idempotency_key='CAF\u00C9')",
                Integer.class);
        assertThat(count).as("大小写＋é→E 预组合替换 → DB 等价合并").isEqualTo(1);
    }

    @Test @Order(9)
    void collationVariantConflictRejected() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "\u00E9clair", objectMapper.createObjectNode().put("v", 1), Instant.now().plusSeconds(60)));
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "e\u0301clair", objectMapper.createObjectNode().put("v", 2), Instant.now().plusSeconds(60)));
                collector.flush();
                return null;
            });
            throw new AssertionError("DB 等价键异载荷必须被拒");
        } catch (SystemIntegrityException expected) {
            assertThat(expected).hasMessageContaining("冲突");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' "
                        + "AND (idempotency_key='\u00E9clair' OR idempotency_key='e\u0301clair')",
                Integer.class);
        assertThat(count).isZero();
    }

    @Test @Order(10)
    void kStarOrigMissFailsClosed() {
        CollationOrderingMapper.TimerKeyRow row = new CollationOrderingMapper.TimerKeyRow();
        row.setTaskTypeOrig("MISSING-TYPE");
        row.setIdempotencyKeyOrig("missing-key");
        row.setGrp(1L);
        assertThatThrownBy(() -> LocalLedgerIntentCollector.mergeTimerGroups(Map.of(), List.of(row)))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("定位失败");
    }

    @Test @Order(11)
    void connectionCollationIsUtf8Mb4AndOriginalBytesRoundTrip() {
        String collation = jdbc.queryForObject("SELECT @@collation_connection", String.class);
        assertThat(collation).as("k*_orig 字节回传依赖连接字符集 utf8mb4（N18）").startsWith("utf8mb4");
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "\u4E2D\u6587\u952E-\u7532", objectMapper.createObjectNode().put("v", 1),
                    Instant.now().plusSeconds(60)));
            collector.flush();
            return null;
        });
        String persisted = jdbc.queryForObject(
                "SELECT idempotency_key FROM sc_timer_task WHERE idempotency_key='\u4E2D\u6587\u952E-\u7532'",
                String.class);
        assertThat(persisted).as("中文键原始字节精确回传（k*_orig 定位前提）").isEqualTo("\u4E2D\u6587\u952E-\u7532");
    }

    @Test @Order(12)
    void dbCollationOrderPinsJavaDivergingPair() {
        // 真实 MySQL 裁定（2026-10-06 实测）："key-A" 先于 "key-a"（三级权重大写在前，与 Java CASE_INSENSITIVE_ORDER 一致）；
        // Java 与 DB 分歧对："key-b" vs "key-å"（U+00E5）：
        // Java CASE_INSENSITIVE_ORDER 按大写折叠码点比较 → 'B'(66) < 'Å'(197) → "key-b" 先于 "key-å"；
        // DB utf8mb4_0900_ai_ci（DUCET）：å 的主权重归 a 组 → A 组先于 B 组 → "key-å" 先于 "key-b"。
        // 以下断言按真实 DB 返回值固定（C-CONC 预期依据）。
        List<CollationOrderingMapper.TimerKeyRow> rows = collationOrderingMapper.orderTimerKeys(List.of(
                new LocalLedgerIntentCollector.TimerKey("TEST_ONCE", "key-b"),
                new LocalLedgerIntentCollector.TimerKey("TEST_ONCE", "key-\u00E5")));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getIdempotencyKeyOrig())
                .as("DB 裁定：key-å（主权重 a 组）先于 key-b（C-CONC 预期依据）")
                .isEqualTo("key-\u00E5");
        assertThat(rows.get(1).getIdempotencyKeyOrig()).isEqualTo("key-b");
    }

    @Test @Order(13)
    void concurrentDivergentPairFirstLockFollowsDbOrder() throws Exception {
        // C-CONC：两线程独立事务反序录入分歧对（DB 序 key-å 先 key-b；旧 Java 排序相反）。
        // 持锁方保持事务打开，反序方按实现排序阻塞于其首键；独立观察连接 data_locks 的
        // WAITING LOCK_DATA 断言首锁为 DB 序首键。只证排序，不宣称无死锁。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier holderFlushed = new CyclicBarrier(2);
            CyclicBarrier release = new CyclicBarrier(2);
            Future<?> holder = pool.submit(() -> new TransactionTemplate(txManager).execute(status -> {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "conc-key-b", objectMapper.createObjectNode().put("v", 1),
                        Instant.now().plusSeconds(60)));
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "conc-key-\u00E5", objectMapper.createObjectNode().put("v", 1),
                        Instant.now().plusSeconds(60)));
                collector.flush();
                awaitQuietly(holderFlushed);
                awaitQuietly(release);
                return null;
            }));
            awaitQuietly(holderFlushed);
            Future<?> contender = pool.submit(() -> new TransactionTemplate(txManager).execute(status -> {
                // 反序录入：记录顺序与 DB collation 排序相反，锁序必须由实现排序决定
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "conc-key-\u00E5", objectMapper.createObjectNode().put("v", 1),
                        Instant.now().plusSeconds(60)));
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "conc-key-b", objectMapper.createObjectNode().put("v", 1),
                        Instant.now().plusSeconds(60)));
                collector.flush();
                return null;
            }));
            try {
                String firstLock = awaitWaitingLockData(10_000);
                assertThat(firstLock)
                        .as("分歧对首锁必须按 DB collation 序（key-å 先于 key-b）；旧 Java 排序将反向")
                        .contains("conc-key-\u00E5");
            } finally {
                awaitQuietly(release);
            }
            holder.get(20, TimeUnit.SECONDS);
            contender.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 受测事务连接内取得本连接 performance_schema 线程 ID。 */
    private static long currentThreadId() {
        try (java.sql.Statement statement = DataSourceUtils.getConnection(dataSource).createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery("SELECT PS_CURRENT_THREAD_ID()")) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /** 独立观察连接（root）：受测 THREAD_ID 语句历史窗口。 */
    private static long maxStatementEventId(long threadId) {
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             java.sql.Statement statement = admin.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery(
                     "SELECT COALESCE(MAX(EVENT_ID), 0) FROM performance_schema.events_statements_history_long "
                             + "WHERE THREAD_ID = " + threadId)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /** 独立观察连接（root）：受测线程 EVENT_ID 大于 baseline 的语句文本。 */
    private static List<String> observerStatementTexts(long threadId, long baseline) {
        List<String> texts = new ArrayList<>();
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             java.sql.Statement statement = admin.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery(
                     "SELECT SQL_TEXT FROM performance_schema.events_statements_history_long "
                             + "WHERE THREAD_ID = " + threadId + " AND SQL_TEXT IS NOT NULL "
                             + "AND EVENT_ID > " + baseline + " ORDER BY EVENT_ID")) {
            while (resultSet.next()) {
                texts.add(resultSet.getString(1));
            }
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
        return texts;
    }

    /** 独立观察连接（root）：受测线程当前 data_locks 行数。 */
    private static int dataLocksForThread(long threadId) {
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             java.sql.Statement statement = admin.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM performance_schema.data_locks WHERE THREAD_ID = " + threadId)) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /** 独立观察连接（root）：轮询直至出现 WAITING 锁并返回其 LOCK_DATA（本容器此刻唯一等待者为首锁）。 */
    private static String awaitWaitingLockData(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                 java.sql.Statement statement = admin.createStatement();
                 java.sql.ResultSet resultSet = statement.executeQuery(
                         "SELECT LOCK_DATA FROM performance_schema.data_locks WHERE LOCK_STATUS='WAITING' LIMIT 1")) {
                if (resultSet.next()) {
                    return resultSet.getString(1);
                }
            } catch (SQLException failure) {
                throw new RuntimeException(failure);
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("观察窗口内未见 WAITING 锁（数据锁等待未按预期出现）");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
        }
    }

    /** 并发协同屏障等待（超时即失败，避免持锁事务悬挂）。 */
    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new RuntimeException("并发协同屏障等待失败", failure);
        }
    }

    /** 独立连接（root）直接插入标记任务行并立即提交（RR 视图负例标记）。 */
    private static void insertMarkerTask(String idempotencyKey) {
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             java.sql.Statement statement = admin.createStatement()) {
            statement.executeUpdate("INSERT INTO sc_timer_task (task_no, task_type, idempotency_key, "
                    + "payload_version, payload_json, schedule_type, next_fire_at, status, max_attempts, "
                    + "created_at, updated_at) VALUES ('"
                    + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20)
                    + "', 'MARKER', '" + idempotencyKey
                    + "', 1, '{\"m\":1}', 'ONCE', NOW(3), 0, 3, NOW(3), NOW(3))");
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
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
