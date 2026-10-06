package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.dataobject.OutboxAppendIdentityDO;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.OutboxAppendIdentityMapper;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventMapper;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B0切片3：两张独立身份账本DDL（061迁移）与排他建槽防锁环协议集成测试。
 * 设计依据：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#72-独立不可变身份账本确定消除重复追加等待源行
 * 与 #721-冻结排他建槽sql与首次所有权。
 * R3-02O（rev3，D3/D4）：Outbox 段（test02–test08）按同模块 privateLookupIn 反射桥驱动
 * OutboxEventRepository 内 main 私有嵌套可信追加底座（真实MySQL）；Timer 段公开 API 逐字节不动。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AppendScheduleIdentityLedgerIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_identity_ledger");
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static DataSource dataSource;
    private static AppendScheduleIdentityLedgerService ledgerService;
    private static OutboxEventMapper sourceMapper;
    private static OutboxAppendIdentityMapper ledgerMapper;
    private static BusinessNoGenerator businessNoGenerator;
    private static Object core;
    private static PlatformTransactionManager supplyChainTransactionManager;
    private static PlatformTransactionManager secondTransactionManager;
    private static org.springframework.transaction.support.TransactionTemplate tx;

    @BeforeAll
    static void start() throws Exception {
        MYSQL.start();
        try {
            MYSQL.execInContainer("mysql", "-uroot", "-p" + MYSQL.getPassword(), "-e",
                    "GRANT PROCESS ON *.* TO '" + MYSQL.getUsername() + "'@'%'; "
                            + "GRANT SELECT ON performance_schema.* TO '" + MYSQL.getUsername() + "'@'%'; FLUSH PRIVILEGES;");
        } catch (Exception ignored) {
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
        ledgerService = context.getBean(AppendScheduleIdentityLedgerService.class);
        sourceMapper = context.getBean(OutboxEventMapper.class);
        ledgerMapper = context.getBean(OutboxAppendIdentityMapper.class);
        businessNoGenerator = context.getBean(BusinessNoGenerator.class);
        supplyChainTransactionManager = context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class);
        secondTransactionManager = context.getBean("secondTransactionManager", PlatformTransactionManager.class);
        core = TrustedOutboxAppendCoreBridge.newCore(sourceMapper, businessNoGenerator, ledgerMapper, dataSource);
        tx = new org.springframework.transaction.support.TransactionTemplate(supplyChainTransactionManager);

        // 创建本地领域效果表用于验证事务原子性
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_domain_effect (" +
                "command_id VARCHAR(64) NOT NULL, attempt_id BINARY(16) NOT NULL PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB");
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        MYSQL.stop();
    }

    @Test
    @Order(1)
    void test01_liquibaseMigrates061AndEnforcesCheckConstraints() throws Exception {
        // 1. 验证两张独立身份账本表在真实 MySQL 中创建成功
        Integer outboxTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'sc_outbox_append_identity'",
                Integer.class);
        assertThat(outboxTableCount).as("sc_outbox_append_identity 表必须存在").isEqualTo(1);

        Integer timerTableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'sc_timer_schedule_identity'",
                Integer.class);
        assertThat(timerTableCount).as("sc_timer_schedule_identity 表必须存在").isEqualTo(1);

        // 2. 验证 sc_outbox_append_identity CHECK 约束
        // 2.1 状态枚举 CHECK (binding_status IN (10, 20))
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sc_outbox_append_identity(aggregate_type, aggregate_id, aggregate_version, event_type, binding_status, reservation_nonce, target_type, target_name, payload_version, comparison_protocol, payload_json, created_at) " +
                        "VALUES('ORDER', '1001', 1, 'ORDER_CREATED', 99, X'0102030405060708090a0b0c0d0e0f10', 'LOCAL', 'test', 1, 'LEGACY_JSON_V1', '{}', CURRENT_TIMESTAMP(3))"))
                .hasMessageContaining("3819")
                .hasMessageContaining("Check constraint");

        // 2.2 RESERVED 时 event_id 非空违背 CHECK 约束
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sc_outbox_append_identity(aggregate_type, aggregate_id, aggregate_version, event_type, binding_status, reservation_nonce, target_type, target_name, payload_version, comparison_protocol, payload_json, event_id, created_at) " +
                        "VALUES('ORDER', '1001', 1, 'ORDER_CREATED', 10, X'0102030405060708090a0b0c0d0e0f10', 'LOCAL', 'test', 1, 'LEGACY_JSON_V1', '{}', 'EVT_1001', CURRENT_TIMESTAMP(3))"))
                .hasMessageContaining("3819")
                .hasMessageContaining("Check constraint");

        // 2.3 BOUND 时 event_id 或 bound_at 为空违背 CHECK 约束
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sc_outbox_append_identity(aggregate_type, aggregate_id, aggregate_version, event_type, binding_status, reservation_nonce, target_type, target_name, payload_version, comparison_protocol, payload_json, event_id, created_at, bound_at) " +
                        "VALUES('ORDER', '1001', 1, 'ORDER_CREATED', 20, X'0102030405060708090a0b0c0d0e0f10', 'LOCAL', 'test', 1, 'LEGACY_JSON_V1', '{}', NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))"))
                .hasMessageContaining("3819")
                .hasMessageContaining("Check constraint");

        // 3. 验证 sc_timer_schedule_identity CHECK 约束
        // 3.1 状态枚举 CHECK (binding_status IN (10, 20))
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sc_timer_schedule_identity(task_type, idempotency_key, binding_status, reservation_nonce, payload_version, comparison_protocol, payload_json, created_at) " +
                        "VALUES('REEVALUATE', 'KEY_1001', 99, X'0102030405060708090a0b0c0d0e0f10', 1, 'LEGACY_JSON_V1', '{}', CURRENT_TIMESTAMP(3))"))
                .hasMessageContaining("3819")
                .hasMessageContaining("Check constraint");

        // 3.2 RESERVED 时 task_no / task_id / bound_at 非空违背 CHECK 约束
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sc_timer_schedule_identity(task_type, idempotency_key, binding_status, reservation_nonce, payload_version, comparison_protocol, payload_json, task_no, created_at) " +
                        "VALUES('REEVALUATE', 'KEY_1001', 10, X'0102030405060708090a0b0c0d0e0f10', 1, 'LEGACY_JSON_V1', '{}', 'TSK_1001', CURRENT_TIMESTAMP(3))"))
                .hasMessageContaining("3819")
                .hasMessageContaining("Check constraint");

        // 3.3 BOUND 时 task_no / task_id / bound_at 为空违背 CHECK 约束
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sc_timer_schedule_identity(task_type, idempotency_key, binding_status, reservation_nonce, payload_version, comparison_protocol, payload_json, task_no, task_id, created_at, bound_at) " +
                        "VALUES('REEVALUATE', 'KEY_1001', 20, X'0102030405060708090a0b0c0d0e0f10', 1, 'LEGACY_JSON_V1', '{}', 'TSK_1001', NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))"))
                .hasMessageContaining("3819")
                .hasMessageContaining("Check constraint");
    }

    @Test
    @Order(2)
    void test02_trustedCoreFirstWriteReadBackBindAndTimerReservation() {
        LocalDateTime now = LocalDateTime.now();

        // 1. 底座首次完整追加：真实严格源INSERT1、同连接按真实内部id当前锁读全属性、绑定CAS1、持久eventId
        String eventId = tx.execute(status -> {
            String first = TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "2001", 1L, "ORDER_PAID", "order-handler", "{\"orderId\":\"2001\"}", now));
            assertThat(first).as("底座必须返回真实持久eventId").startsWith("EVT_");
            List<String> statements = sessionStatementTexts();
            assertThat(statements.stream().filter(s -> s.contains("sc_outbox_append_identity") && s.contains("INSERT")).count())
                    .as("账本排他建槽INSERT必须恰1条").isEqualTo(1);
            assertThat(statements.stream().filter(s -> s.contains("sc_outbox_append_identity") && s.contains("FOR UPDATE")).count())
                    .as("账本当前锁读必须恰1条").isEqualTo(1);
            assertThat(statements.stream().filter(s -> s.contains("sc_outbox_event") && s.contains("INSERT")).count())
                    .as("真实源严格INSERT必须恰1条").isEqualTo(1);
            assertThat(statements.stream().filter(s -> s.contains("sc_outbox_event") && s.contains("FOR UPDATE")).count())
                    .as("同连接按真实内部id源当前读必须恰1条").isEqualTo(1);
            assertThat(statements.stream().filter(s -> s.contains("UPDATE sc_outbox_append_identity")).count())
                    .as("绑定CAS必须恰1条").isEqualTo(1);
            return first;
        });

        Map<String, Object> sourceRow = jdbc.queryForMap(
                "SELECT status, attempt_count, fencing_token, target_type, target_name, payload_version, trace_id FROM sc_outbox_event WHERE event_id = ?",
                eventId);
        assertThat(((Number) sourceRow.get("status")).shortValue()).as("初始状态必须PENDING").isEqualTo((short) 0);
        assertThat(((Number) sourceRow.get("attempt_count")).intValue()).as("初始attempt必须0").isEqualTo(0);
        assertThat(((Number) sourceRow.get("fencing_token")).longValue()).as("初始token必须0").isEqualTo(0L);
        assertThat(sourceRow.get("target_type")).isEqualTo("LOCAL");
        assertThat(sourceRow.get("target_name")).isEqualTo("order-handler");
        assertThat(((Number) sourceRow.get("payload_version")).intValue()).isEqualTo(1);
        assertThat(sourceRow.get("trace_id")).isEqualTo("trace-2001");

        Map<String, Object> ledgerRow = jdbc.queryForMap(
                "SELECT binding_status, event_id, bound_at FROM sc_outbox_append_identity WHERE aggregate_id = '2001'");
        assertThat(((Number) ledgerRow.get("binding_status")).shortValue()).isEqualTo((short) 20);
        assertThat(ledgerRow.get("event_id")).isEqualTo(eventId);
        assertThat(ledgerRow.get("bound_at")).isNotNull();

        // 2. 同载荷重复追加 -> 原eventId；重复路径源 SELECT/INSERT/锁 全部为 0
        String duplicate = tx.execute(status -> {
            long baseline = maxStatementEventId();
            String second = TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "2001", 1L, "ORDER_PAID", "order-handler", "{\"orderId\":\"2001\"}", now));
            List<String> statements = sessionStatementTextsAfter(baseline);
            assertThat(statements.stream().filter(s -> s.contains("sc_outbox_event")).count())
                    .as("重复路径不得有任何源SQL（SELECT/INSERT/锁均0）").isZero();
            assertThat(statements.stream().filter(s -> s.contains("sc_outbox_append_identity") && s.contains("INSERT")).count())
                    .as("重复路径账本排他upsert恰1条").isEqualTo(1);
            assertThat(statements.stream().filter(s -> s.contains("sc_outbox_append_identity") && s.contains("FOR UPDATE")).count())
                    .as("重复路径账本当前锁读恰1条").isEqualTo(1);
            return second;
        });
        assertThat(duplicate).isEqualTo(eventId);

        // 3. Timer 首次排他建槽 -> RESERVED 并单次 CAS 绑定 -> BOUND
        tx.execute(status -> {
            var timerRes1 = ledgerService.reserveTimerSlot(
                    "SWEEP", "TIMER_KEY_2001", 1, "LEGACY_JSON_V1",
                    "{\"batchSize\":50}", now);
            assertThat(timerRes1.firstReserved()).as("Timer首次建槽必须返回 firstReserved=true").isTrue();
            assertThat(timerRes1.slotId()).isNotNull();
            assertThat(timerRes1.nonce()).isNotNull().hasSize(16);

            ledgerService.bindTimerTask(timerRes1.slotId(), timerRes1.nonce(), 9999L, "TSK_2001", LocalDateTime.now());
            return null;
        });

        Map<String, Object> timerRow = jdbc.queryForMap(
                "SELECT binding_status, task_id, task_no, bound_at FROM sc_timer_schedule_identity WHERE idempotency_key = 'TIMER_KEY_2001'");
        assertThat(((Number) timerRow.get("binding_status")).shortValue()).isEqualTo((short) 20);
        assertThat(((Number) timerRow.get("task_id")).longValue()).isEqualTo(9999L);
        assertThat(timerRow.get("task_no")).isEqualTo("TSK_2001");
        assertThat(timerRow.get("bound_at")).isNotNull();

        // 4. Timer 同载荷重复建槽 -> 幂等复用返回 BOUND
        tx.execute(status -> {
            var timerRes2 = ledgerService.reserveTimerSlot(
                    "SWEEP", "TIMER_KEY_2001", 1, "LEGACY_JSON_V1",
                    "{\"batchSize\":50}", now);
            assertThat(timerRes2.firstReserved()).isFalse();
            assertThat(timerRes2.existingTaskId()).isEqualTo(9999L);
            assertThat(timerRes2.existingTaskNo()).isEqualTo("TSK_2001");
            return null;
        });
    }

    @Test
    @Order(3)
    void test03_payloadMismatchAndCasFailureRollback() {
        LocalDateTime now = LocalDateTime.now();

        // 1. 槽已被 BOUND，同键异载荷必须抛出 SystemIntegrityException（不触源）
        tx.execute(status -> {
            assertThatThrownBy(() -> TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "2001", 1L, "ORDER_PAID", "order-handler", "{\"orderId\":\"2001_DIFFERENT_PAYLOAD\"}", now)))
                    .isInstanceOf(SystemIntegrityException.class)
                    .hasMessageContaining("Outbox身份账本载荷协议或内容冲突");

            // 2. 槽已被 BOUND，同键异 targetName 必须抛出 SystemIntegrityException
            assertThatThrownBy(() -> TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "2001", 1L, "ORDER_PAID", "other-handler", "{\"orderId\":\"2001\"}", now)))
                    .isInstanceOf(SystemIntegrityException.class)
                    .hasMessageContaining("Outbox身份账本载荷协议或内容冲突");

            // 3. Timer 槽已被 BOUND，同键异载荷必须抛出 SystemIntegrityException
            assertThatThrownBy(() -> ledgerService.reserveTimerSlot(
                    "SWEEP", "TIMER_KEY_2001", 1, "LEGACY_JSON_V1",
                    "{\"batchSize\":999}", now))
                    .isInstanceOf(SystemIntegrityException.class)
                    .hasMessageContaining("Timer身份账本载荷协议或内容冲突");
            return null;
        });

        // 4. 真实SQL边界故障注入：bindEventCas 收到篡改 nonce -> 更新0行 -> SystemIntegrityException -> 全回滚
        Object corruptCore = TrustedOutboxAppendCoreBridge.newCore(sourceMapper, businessNoGenerator,
                CorruptNonceLedgerMapper.wrap(ledgerMapper), dataSource);
        assertThatThrownBy(() -> tx.execute(status -> TrustedOutboxAppendCoreBridge.append(corruptCore,
                command("ORDER", "3001", 1L, "ORDER_CREATED", "test-handler", "{\"orderId\":\"3001\"}", now))))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("Outbox身份账本绑定CAS更新行数不为1");

        // 独立连接读回：源与账本全无残留
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_append_identity WHERE aggregate_id = '3001'", Integer.class))
                .as("CAS失败后账本必须无残留").isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_id = '3001'", Integer.class))
                .as("CAS失败后源必须无残留").isEqualTo(0);

        // 5. 目标类型非 LOCAL 在命令构造即拒绝（DAL零交互）
        assertThatThrownBy(() -> TrustedOutboxAppendCoreBridge.newCommand(
                "ORDER", "3002", 1L, "ORDER_CREATED", "HTTP", "test", 1, "LEGACY_JSON_V1",
                "{\"orderId\":\"3002\"}", "trace-3002", null, null, now))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("仅支持LOCAL投递目标");
    }

    @Test
    @Order(4)
    void test04_repeatableReadCurrentLockReadAndPreCommitGuard() throws Exception {
        LocalDateTime now = LocalDateTime.now();

        // Part 1: 旧 RR 视图下的当前锁读
        CountDownLatch txAStarted = new CountDownLatch(1);
        CountDownLatch txBCommitted = new CountDownLatch(1);
        AtomicReference<String> txAResult = new AtomicReference<>();

        Thread threadA = new Thread(() -> {
            tx.execute(status -> {
                // 普通一致性读，建立旧 RR 快照
                jdbc.queryForList("SELECT id FROM sc_outbox_append_identity");
                txAStarted.countDown();
                try {
                    txBCommitted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                // 在旧 RR 快照下驱动底座：内部 SELECT ... FOR UPDATE 当前锁读必须看到 B 事务已提交的 BOUND
                txAResult.set(TrustedOutboxAppendCoreBridge.append(core,
                        command("ORDER", "4001", 1L, "ORDER_CREATED", "test", "{\"orderId\":\"4001\"}", now)));
                return null;
            });
        });
        threadA.start();
        txAStarted.await();

        // 事务 B 在线程 A 建立快照之后首次追加并提交
        tx.execute(status -> {
            String first = TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "4001", 1L, "ORDER_CREATED", "test", "{\"orderId\":\"4001\"}", now));
            assertThat(first).startsWith("EVT_");
            return null;
        });
        txBCommitted.countDown();
        threadA.join();

        String boundEventId = jdbc.queryForObject(
                "SELECT event_id FROM sc_outbox_append_identity WHERE aggregate_id = '4001'", String.class);
        assertThat(txAResult.get()).as("旧RR视图下当前锁读必须看到B事务提交的BOUND").isEqualTo(boundEventId);

        // Part 2: 最终提交前残留守卫 (拒绝未绑定的 RESERVED 槽) + 线程本地清理
        assertThatThrownBy(() -> tx.execute(status -> {
            TrustedOutboxAppendCoreBridge.guardReservation(core, "ORDER|5001|1|ORDER_CREATED");
            // 故意不绑定，直接退出让事务尝试 commit
            return null;
        })).isInstanceOf(Exception.class)
                .hasMessageContaining("事务提交前守卫拒绝残留RESERVED账本槽");

        // 数据库验证：事务被回滚，无残留 RESERVED 记录
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_append_identity WHERE aggregate_id = '5001'",
                Integer.class);
        assertThat(count).isEqualTo(0);

        // 线程本地守卫状态已清理：同线程同键新事务可全新首次追加（无重入阻塞、无旧状态污染）
        tx.execute(status -> {
            String fresh = TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "5001", 1L, "ORDER_CREATED", "test", "{\"orderId\":\"5001\"}", now));
            assertThat(fresh).startsWith("EVT_");
            return null;
        });
    }

    @Test
    @Order(5)
    void test05_threeTransactionConcurrencyLatchCommitAndRollback() throws Exception {
        LocalDateTime now = LocalDateTime.now();

        // ========================================================
        // Part 1: 首次创建者提交 + 两个等待者排他串行化
        // ========================================================
        CountDownLatch tx1ReservedA = new CountDownLatch(1);
        CountDownLatch tx1CommitSignalA = new CountDownLatch(1);
        CountDownLatch allDoneA = new CountDownLatch(2);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<String> winnerA = new AtomicReference<>();
        List<String> resultsA = Collections.synchronizedList(new ArrayList<>());

        Thread tx1ThreadA = new Thread(() -> {
            tx.execute(status -> {
                String first = TrustedOutboxAppendCoreBridge.append(core,
                        command("ORDER", "6001", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"6001\"}", now));
                assertThat(first).startsWith("EVT_");
                winnerA.set(first);
                tx1ReservedA.countDown();
                try {
                    tx1CommitSignalA.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return null;
            });
        });
        tx1ThreadA.start();
        tx1ReservedA.await();

        Runnable waiterA = () -> {
            try {
                String eventId = tx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                        command("ORDER", "6001", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"6001\"}", now)));
                resultsA.add(eventId);
            } catch (Throwable t) {
                errorA.set(t);
            } finally {
                allDoneA.countDown();
            }
        };

        Thread tx2ThreadA = new Thread(waiterA);
        Thread tx3ThreadA = new Thread(waiterA);
        tx2ThreadA.start();
        tx3ThreadA.start();

        // 验证 MySQL 中真实观察到两个请求事务在锁等待，再释放创建者
        waitForDataLockWaits("sc_outbox_append_identity", 2);

        tx1CommitSignalA.countDown();
        tx1ThreadA.join();

        assertThat(allDoneA.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(errorA.get()).isNull();
        assertThat(resultsA).containsExactlyInAnyOrder(winnerA.get(), winnerA.get());

        // ========================================================
        // Part 2: 首次创建者回滚 + 两个等待者在空表/末尾键竞争
        // （获审协议：本片不实施重跑，真实 1213/40001 死锁原样传播、不吞不retry）
        // ========================================================
        CountDownLatch tx1ReservedB = new CountDownLatch(1);
        CountDownLatch tx1RollbackSignalB = new CountDownLatch(1);
        CountDownLatch allDoneB = new CountDownLatch(2);
        AtomicReference<Throwable> errorB = new AtomicReference<>();
        AtomicInteger waiterAttemptsB = new AtomicInteger(0);
        List<String> resultsB = Collections.synchronizedList(new ArrayList<>());

        Thread tx1ThreadB = new Thread(() -> {
            try {
                tx.execute(status -> {
                    TrustedOutboxAppendCoreBridge.append(core,
                            command("ORDER", "7001", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"7001\"}", now));
                    tx1ReservedB.countDown();
                    try {
                        tx1RollbackSignalB.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    throw new RuntimeException("Tx1 触发显式回滚");
                });
            } catch (Exception expected) {
                // Tx1 正常回滚
            }
        });
        tx1ThreadB.start();
        tx1ReservedB.await();

        Runnable waiterB = () -> {
            try {
                waiterAttemptsB.incrementAndGet();
                String eventId = tx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                        command("ORDER", "7001", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"7001\"}", now)));
                resultsB.add(eventId);
            } catch (Throwable t) {
                errorB.set(t);
            } finally {
                allDoneB.countDown();
            }
        };

        Thread tx2ThreadB = new Thread(waiterB);
        Thread tx3ThreadB = new Thread(waiterB);
        tx2ThreadB.start();
        tx3ThreadB.start();

        // 验证 MySQL 中真实观察到两个请求事务在锁等待，再释放创建者回滚
        waitForDataLockWaits("sc_outbox_append_identity", 2);

        tx1RollbackSignalB.countDown();
        tx1ThreadB.join();

        assertThat(allDoneB.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(waiterAttemptsB.get()).as("无重试：两等待者各恰好执行一次").isEqualTo(2);
        // 至少一个等待者完成首写并绑定；失败者（若发生）必须是真实 1213/40001 死锁且原样传播
        assertThat(resultsB).as("至少一个等待者必须成功返回eventId").isNotEmpty();
        if (errorB.get() != null) {
            assertThat(hasExactDeadlockInChain(errorB.get()))
                    .as("唯一允许的等待者失败是真实 1213/40001 死锁").isTrue();
        }
        String boundB = jdbc.queryForObject(
                "SELECT event_id FROM sc_outbox_append_identity WHERE aggregate_id = '7001' AND binding_status = 20",
                String.class);
        assertThat(resultsB).allMatch(eventId -> eventId.equals(boundB));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_append_identity WHERE aggregate_id = '7001' AND binding_status = 10",
                Integer.class)).as("不得残留RESERVED").isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_id = '7001'", Integer.class))
                .as("源恰好1行").isEqualTo(1);
    }

    @Test
    @Order(6)
    void test06_lockWaitTimeout1205AndSourceLockDuplicateDoesNotWait() throws Exception {
        LocalDateTime now = LocalDateTime.now();

        // Part 1: 真实 1205 锁等待超时：不重跑、全回滚
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger(0);

        ExecutorService holderPool = Executors.newSingleThreadExecutor();
        try {
            Future<?> lockHolder = holderPool.submit(() -> {
                tx.execute(status -> {
                    String first = TrustedOutboxAppendCoreBridge.append(core,
                            command("ORDER", "8001", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"8001\"}", now));
                    assertThat(first).startsWith("EVT_");
                    lockAcquired.countDown();
                    try {
                        releaseLock.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    status.setRollbackOnly();
                    return null;
                });
            });

            lockAcquired.await(5, TimeUnit.SECONDS);

            // 竞争者尝试追加，设置 innodb_lock_wait_timeout=1，触发真实 1205 超时
            assertThatThrownBy(() -> tx.execute(status -> {
                attempts.incrementAndGet();
                jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
                return TrustedOutboxAppendCoreBridge.append(core,
                        command("ORDER", "8001", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"8001\"}", now));
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);

            // 验证 1205 错误绝不重跑，执行次数严格为 1
            assertThat(attempts.get()).as("1205 锁等待超时严禁重试，尝试次数必须为 1").isEqualTo(1);

            releaseLock.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            holderPool.shutdownNow();
        }

        // holder 回滚后无残留
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_append_identity WHERE aggregate_id = '8001'", Integer.class))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_id = '8001'", Integer.class))
                .isEqualTo(0);

        // Part 2: 持旧源行锁重复追加不等待源
        String bound = tx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                command("ORDER", "8002", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"8002\"}", now)));
        Long sourceId = jdbc.queryForObject("SELECT id FROM sc_outbox_event WHERE event_id = ?", Long.class, bound);

        CountDownLatch sourceLocked = new CountDownLatch(1);
        CountDownLatch releaseSourceLock = new CountDownLatch(1);
        ExecutorService sourceHolderPool = Executors.newSingleThreadExecutor();
        try {
            Future<?> sourceHolder = sourceHolderPool.submit(() -> {
                tx.execute(status -> {
                    jdbc.queryForMap("SELECT id FROM sc_outbox_event WHERE id = ? FOR UPDATE", sourceId);
                    sourceLocked.countDown();
                    try {
                        releaseSourceLock.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    status.setRollbackOnly();
                    return null;
                });
            });
            sourceLocked.await(5, TimeUnit.SECONDS);

            // 重复追加在锁等待超时1秒内完成：不等待源行锁
            String duplicate = tx.execute(status -> {
                jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
                return TrustedOutboxAppendCoreBridge.append(core,
                        command("ORDER", "8002", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"8002\"}", now));
            });
            assertThat(duplicate).as("持源锁重复必须立即返回原eventId").isEqualTo(bound);

            releaseSourceLock.countDown();
            sourceHolder.get(5, TimeUnit.SECONDS);
        } finally {
            releaseSourceLock.countDown();
            sourceHolderPool.shutdownNow();
        }
    }

    @Test
    @Order(7)
    void test07_sourceDuplicate1062DoesNotRetry() {
        LocalDateTime now = LocalDateTime.now();
        // 预置源表重复数据（同一四列聚合键，不同eventId）
        jdbc.update("INSERT INTO sc_outbox_event (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, " +
                "target_type, target_name, payload_version, payload_json, available_at, status, max_attempts, trace_id, created_at, updated_at) " +
                "VALUES ('EVT_DUP_9001', 'ORDER', '9001', 1, 'ORDER_PAID', 'LOCAL', 'handler', 1, '{}', CURRENT_TIMESTAMP(3), 0, 3, 'trace-1', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))");

        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> tx.execute(status -> {
            attempts.incrementAndGet();
            return TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "9001", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"9001\"}", now));
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);

        // 验证 1062 错误绝不重跑，尝试次数必须为 1
        assertThat(attempts.get()).as("1062 唯一键冲突严禁重试，尝试次数必须为 1").isEqualTo(1);

        // 账本已回滚，无残留
        Integer ledgerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_append_identity WHERE aggregate_id = '9001'",
                Integer.class);
        assertThat(ledgerCount).isEqualTo(0);
        // 源仍只有预置行，无 EVT_ 新行残留
        Integer sourceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_id = '9001'",
                Integer.class);
        assertThat(sourceCount).isEqualTo(1);
    }

    @Test
    @Order(8)
    void test08_checkViolationRollbackOnlyAndTransactionGuards() {
        LocalDateTime now = LocalDateTime.now();

        // 1. 追加完成后在真实SQL边界破坏 CHECK 约束（3819）-> 整事务回滚、无残留
        assertThatThrownBy(() -> tx.execute(status -> {
            TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "9002", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"9002\"}", now));
            // 违反 CHECK 约束更新 binding_status = 99
            jdbc.update("UPDATE sc_outbox_append_identity SET binding_status = 99 WHERE aggregate_id = '9002'");
            return null;
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_append_identity WHERE aggregate_id = '9002'", Integer.class))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_id = '9002'", Integer.class))
                .isEqualTo(0);

        // 2. rollbackOnly 事务拒绝（设置事务绑定 ConnectionHolder 的 rollback-only 标志）
        assertThatThrownBy(() -> tx.execute(status -> {
            org.springframework.jdbc.datasource.ConnectionHolder holder =
                    (org.springframework.jdbc.datasource.ConnectionHolder)
                            org.springframework.transaction.support.TransactionSynchronizationManager.getResource(dataSource);
            holder.setRollbackOnly();
            return TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "9003", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"9003\"}", now));
        })).isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("已标记回滚");

        // 3. 错manager：第二事务管理器（独立DataSource资源键）下拒绝
        TransactionTemplate secondTx = new TransactionTemplate(secondTransactionManager);
        assertThatThrownBy(() -> secondTx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                command("ORDER", "9004", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"9004\"}", now))))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("未绑定供应链事务连接资源");

        // 4. read-only 事务拒绝
        TransactionTemplate readOnlyTx = new TransactionTemplate(supplyChainTransactionManager);
        readOnlyTx.setReadOnly(true);
        assertThatThrownBy(() -> readOnlyTx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                command("ORDER", "9004", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"9004\"}", now))))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("只读事务");

        // 5. 无事务拒绝
        assertThatThrownBy(() -> TrustedOutboxAppendCoreBridge.append(core,
                command("ORDER", "9004", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"9004\"}", now)))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("必须在有效写事务内执行");

        // 6. 未完成槽重入拒绝 + 提交守卫联动回滚 + 线程本地清理
        assertThatThrownBy(() -> tx.execute(status -> {
            TrustedOutboxAppendCoreBridge.guardReservation(core, "ORDER|9005|1|ORDER_PAID");
            assertThatThrownBy(() -> TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "9005", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"9005\"}", now)))
                    .isInstanceOf(SystemIntegrityException.class)
                    .hasMessageContaining("拒绝重入未完成账本槽");
            return null;
        })).isInstanceOf(Exception.class)
                .hasMessageContaining("事务提交前守卫拒绝残留RESERVED账本槽");

        // 清理后同键同线程可全新追加
        tx.execute(status -> {
            String fresh = TrustedOutboxAppendCoreBridge.append(core,
                    command("ORDER", "9005", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"9005\"}", now));
            assertThat(fresh).startsWith("EVT_");
            return null;
        });
    }

    private static Object command(String aggregateType, String aggregateId, long aggregateVersion,
                                  String eventType, String targetName, String payloadJson, LocalDateTime now) {
        return TrustedOutboxAppendCoreBridge.newCommand(aggregateType, aggregateId, aggregateVersion,
                eventType, "LOCAL", targetName, 1, "LEGACY_JSON_V1", payloadJson,
                "trace-" + aggregateId, null, null, now);
    }

    /** 当前事务连接的 performance_schema 语句历史（真实SQL逐条证据）；不得关闭事务绑定连接。 */
    private static List<String> sessionStatementTexts() {
        return sessionStatementTextsAfter(0L);
    }

    /** 返回 EVENT_ID 大于 baseline 的语句文本（用于隔离当前事务语句，避免历史污染）。 */
    private static List<String> sessionStatementTextsAfter(long baselineEventId) {
        List<String> texts = new ArrayList<>();
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (java.sql.Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(
                     "SELECT SQL_TEXT FROM performance_schema.events_statements_history "
                             + "WHERE THREAD_ID = PS_CURRENT_THREAD_ID() AND SQL_TEXT IS NOT NULL "
                             + "AND EVENT_ID > " + baselineEventId + " ORDER BY EVENT_ID")) {
            while (resultSet.next()) {
                texts.add(resultSet.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return texts;
    }

    private static long maxStatementEventId() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (java.sql.Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(
                     "SELECT COALESCE(MAX(EVENT_ID), 0) FROM performance_schema.events_statements_history "
                             + "WHERE THREAD_ID = PS_CURRENT_THREAD_ID()")) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean hasExactDeadlockInChain(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql) {
                do {
                    if (sql.getErrorCode() == 1213 && "40001".equals(sql.getSQLState())) {
                        return true;
                    }
                    sql = sql.getNextException();
                } while (sql != null);
            }
            current = current.getCause();
        }
        return false;
    }

    private static void waitForDataLockWaits(String tableName, int expectedWaiters) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        int waiters = 0;
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            while (System.nanoTime() < deadline) {
                try (var rs = admin.createStatement().executeQuery(
                        "SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w " +
                                "JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID = l.ENGINE_LOCK_ID " +
                                "WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = '" + tableName + "'")) {
                    if (rs.next()) {
                        waiters = rs.getInt(1);
                    }
                }
                if (waiters >= expectedWaiters) {
                    return;
                }
                Thread.sleep(100);
            }
        }
        assertThat(waiters).as("真实 MySQL 必须在 performance_schema 中观察到等待事务数达到 " + expectedWaiters).isGreaterThanOrEqualTo(expectedWaiters);
    }

    /** 故障注入：bindEventCas 传篡改 nonce（真实SQL边界），其余委派真实 Mapper。 */
    private static final class CorruptNonceLedgerMapper {

        private CorruptNonceLedgerMapper() {}

        static OutboxAppendIdentityMapper wrap(OutboxAppendIdentityMapper delegate) {
            return (OutboxAppendIdentityMapper) java.lang.reflect.Proxy.newProxyInstance(
                    OutboxAppendIdentityMapper.class.getClassLoader(),
                    new Class<?>[]{OutboxAppendIdentityMapper.class},
                    (proxy, method, args) -> {
                        if ("bindEventCas".equals(method.getName()) && args != null && args.length == 4) {
                            byte[] nonce = (byte[]) args[1];
                            byte[] tampered = nonce.clone();
                            tampered[0] ^= 1;
                            return method.invoke(delegate, args[0], tampered, args[2], args[3]);
                        }
                        return method.invoke(delegate, args == null ? null : args);
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, DefaultAppendScheduleIdentityLedgerService.class,
            BusinessNoGenerator.class, UlidGenerator.class, OutboxEventRepository.class})
    static class Fixture {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        DriverManagerDataSource secondDataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(MYSQL.getJdbcUrl());
            dataSource.setUsername(MYSQL.getUsername());
            dataSource.setPassword(MYSQL.getPassword());
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            return dataSource;
        }

        @Bean
        JdbcTransactionManager secondTransactionManager(
                @org.springframework.beans.factory.annotation.Qualifier("secondDataSource") DataSource secondDataSource) {
            return new JdbcTransactionManager(secondDataSource);
        }
    }
}
