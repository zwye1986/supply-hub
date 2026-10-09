package com.aifuli.module.supplychain.infrastructure;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxDeliveryClaimService;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventDO;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventMapper;
import com.aifuli.module.supplychain.infrastructure.timer.TimerClaimService;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper;
import com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 禁止后台扫描争抢的真实代理/Mapper/MySQL恢复夹具，不接业务库或供应商。
 * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算
 * 与 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#33-timer审计与失败处理；
 * 通用SQL只构造技术状态，不伪称目录从零可售。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClaimRecoveryIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_claim_recovery");
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static OutboxDeliveryClaimService outbox;
    private static TimerClaimService timer;
    private static OutboxEventMapper outboxMapper;
    private static TimerTaskMapper timerMapper;
    private static TransactionTemplate tx;
    private static final DeterministicSqlInterceptor SQL = new DeterministicSqlInterceptor();
    private static final Duration LEASE = Duration.ofSeconds(30);

    @BeforeAll
    static void start() throws Exception {
        MYSQL.start();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", MYSQL.getUsername());
        properties.put("aifuli.supply-chain.datasource.password", MYSQL.getPassword());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.timer.enabled", false);
        properties.put("aifuli.supply-chain.outbox.enabled", false);
        context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE)
                .run(properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                        .toArray(String[]::new));
        DataSource dataSource = context.getBean("supplyChainDataSource", DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
        outbox = context.getBean(OutboxDeliveryClaimService.class);
        timer = context.getBean(TimerClaimService.class);
        outboxMapper = context.getBean(OutboxEventMapper.class);
        timerMapper = context.getBean(TimerTaskMapper.class);
        tx = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        context.getBean("supplyChainSqlSessionFactory", SqlSessionFactory.class).getConfiguration().addInterceptor(SQL);
        assertThat(AopUtils.isAopProxy(outbox)).isTrue();
        assertThat(AopUtils.isAopProxy(timer)).isTrue();
        assertThat(context.getBeansOfType(com.aifuli.module.supplychain.infrastructure.timer.TimerEngineLifecycle.class)).isEmpty();
        assertThat(context.getBeansOfType(com.aifuli.module.supplychain.infrastructure.outbox.OutboxDeliveryEngine.class)).isEmpty();
        try (var c = dataSource.getConnection()) {
            var meta = c.getMetaData();
            var connectionProperties = c.unwrap(com.mysql.cj.jdbc.JdbcConnection.class).getPropertySet();
            var pool = dataSource.unwrap(HikariDataSource.class);
            System.out.println("B0_MYSQL_EVIDENCE image=" + MYSQL.getDockerImageName()
                    + " server=" + meta.getDatabaseProductVersion() + " driver=" + meta.getDriverVersion()
                    + " isolation=" + c.getTransactionIsolation()
                    + " useAffectedRows=" + connectionProperties.getBooleanProperty(com.mysql.cj.conf.PropertyKey.useAffectedRows).getValue()
                    + " connectionTimeZone=" + connectionProperties.getStringProperty(com.mysql.cj.conf.PropertyKey.connectionTimeZone).getValue()
                    + " connectTimeoutMs=" + connectionProperties.getIntegerProperty(com.mysql.cj.conf.PropertyKey.connectTimeout).getValue()
                    + " socketTimeoutMs=" + connectionProperties.getIntegerProperty(com.mysql.cj.conf.PropertyKey.socketTimeout).getValue()
                    + " hikariPoolName=" + pool.getPoolName()
                    + " hikariMaxPoolSize=" + pool.getMaximumPoolSize()
                    + " hikariConnectionTimeoutMs=" + pool.getConnectionTimeout()
                    + " session=" + jdbc.queryForMap("SELECT @@transaction_isolation AS isolation_level, "
                    + "@@session.time_zone AS time_zone, @@innodb_lock_wait_timeout AS lock_wait_seconds"));
        }
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    @BeforeEach
    void reset() {
        SQL.reset();
        jdbc.update("DELETE FROM sc_timer_execution");
        jdbc.update("DELETE FROM sc_timer_task");
        jdbc.update("DELETE FROM sc_outbox_event");
    }

    @AfterEach
    void resetHook() { SQL.reset(); }

    @Test
    void expiredOutboxActiveLeaseIgnoresFuturePlanAndReturnsActualClaim() {
        for (short state : new short[]{10, 20}) {
            Long id = event(state, 1, 3, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                    "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)");
            var claims = outbox.claimBatch("node-new", 1, LEASE);
            assertThat(claims).as("已到期活动态不受旧未来计划阻挡").hasSize(1);
            OutboxEventDO persisted = outboxMapper.selectById(id);
            assertThat(claims.getFirst()).usingRecursiveComparison().isEqualTo(persisted);
            assertThat(persisted.getLeaseOwner()).isEqualTo("node-new");
            assertThat(persisted.getStatus()).isEqualTo((short) 10);
            assertThat(persisted.getAttemptCount()).isEqualTo(2);
            assertThat(persisted.getFencingToken()).isEqualTo(8L);
            assertThat(persisted.getVersion()).isEqualTo(5L);
            assertThat(persisted.getLastErrorCode()).isNull();
            jdbc.update("DELETE FROM sc_outbox_event WHERE id=?", id);
        }
    }

    @Test
    void expiredTimerActiveLeaseIgnoresFuturePlanAndReturnsActualIdentity() {
        for (short state : new short[]{10, 20}) {
            Long id = task(state, 1, 3, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                    "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)");
            var claims = timer.claimBatch("node-new", 1, LEASE);
            assertThat(claims).as("已到期Timer活动态不受旧未来计划阻挡").hasSize(1);
            var row = timerMapper.selectById(id);
            var claim = claims.getFirst();
            assertThat(claim.taskId()).isEqualTo(row.getId());
            assertThat(claim.taskNo()).isEqualTo(row.getTaskNo());
            assertThat(claim.taskType()).isEqualTo(row.getTaskType());
            assertThat(claim.payloadJson()).isEqualTo(row.getPayloadJson());
            assertThat(claim.attemptNo()).isEqualTo(row.getAttemptCount()).isEqualTo(2);
            assertThat(claim.fencingToken()).isEqualTo(row.getFencingToken()).isEqualTo(8L);
            assertThat(row.getVersion()).isEqualTo(5L);
            assertThat(row.getLeaseOwner()).isEqualTo("node-new");
            assertThat(row.getLastErrorCode()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_execution", Integer.class)).isZero();
            jdbc.update("DELETE FROM sc_timer_task WHERE id=?", id);
        }
    }

    @Test
    void outboxDurableBudgetExhaustionConvergesWithoutFailOrExtraAttempt() {
        for (short state : new short[]{0, 30, 10, 20}) {
            Long id = event(state, 2, 2, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                    "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)");
            var before = outboxMapper.selectById(id);
            org.assertj.core.api.Assertions.assertThatCode(() ->
                    assertThat(outbox.claimBatch("node-new", 1, LEASE)).isEmpty())
                    .as("持久预算耗尽必须死信，不能尝试第max+1次而违反CHECK").doesNotThrowAnyException();
            var after = outboxMapper.selectById(id);
            assertThat(after.getStatus()).isEqualTo((short) 50);
            assertThat(after.getAttemptCount()).isEqualTo(2);
            assertThat(after.getVersion()).isEqualTo(5L);
            assertThat(after.getFencingToken()).isEqualTo(7L);
            assertThat(after.getLeaseOwner()).isNull();
            assertThat(after.getLeaseUntil()).isNull();
            assertThat(after.getAvailableAt()).isNotNull().isEqualTo(before.getAvailableAt());
            assertThat(after.getLastErrorCode()).isEqualTo("CLAIM_BUDGET_EXHAUSTED");
            assertThat(outbox.claimBatch("node-third", 1, LEASE)).isEmpty();
            jdbc.update("DELETE FROM sc_outbox_event WHERE id=?", id);
        }
        Long id = event((short) 0, 1, 2, "NULL", "CURRENT_TIMESTAMP(3)");
        assertThat(outbox.claimBatch("node-last", 1, LEASE)).hasSize(1);
        // 最后一次领取后未启动Handler就宕机；只有隔离夹具令真实租约到期，不调用fail。
        jdbc.update("UPDATE sc_outbox_event SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=?", id);
        assertThat(outbox.claimBatch("node-recovery", 1, LEASE)).isEmpty();
        assertThat(outboxMapper.selectById(id).getStatus()).isEqualTo((short) 50);
        assertThat(outboxMapper.selectById(id).getAttemptCount()).isEqualTo(2);
    }

    @Test
    void timerDurableBudgetExhaustionConvergesWithoutFabricatedExecution() {
        for (short state : new short[]{0, 30, 10, 20}) {
            Long id = task(state, 2, 2, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                    "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)");
            var before = timerMapper.selectById(id);
            org.assertj.core.api.Assertions.assertThatCode(() ->
                    assertThat(timer.claimBatch("node-new", 1, LEASE)).isEmpty())
                    .as("Timer持久预算耗尽不得越过CHECK或虚构执行").doesNotThrowAnyException();
            var after = timerMapper.selectById(id);
            assertThat(after.getStatus()).isEqualTo((short) 60);
            assertThat(after.getAttemptCount()).isEqualTo(2);
            assertThat(after.getVersion()).isEqualTo(5L);
            assertThat(after.getFencingToken()).isEqualTo(7L);
            assertThat(after.getLeaseOwner()).isNull();
            assertThat(after.getLeaseUntil()).isNull();
            assertThat(after.getNextFireAt()).isNotNull().isEqualTo(before.getNextFireAt());
            assertThat(after.getLastErrorCode()).isEqualTo("CLAIM_BUDGET_EXHAUSTED");
            assertThat(timer.claimBatch("node-third", 1, LEASE)).isEmpty();
            jdbc.update("DELETE FROM sc_timer_task WHERE id=?", id);
        }
        Long id = task((short) 0, 1, 2, "NULL", "CURRENT_TIMESTAMP(3)");
        assertThat(timer.claimBatch("node-last", 1, LEASE)).hasSize(1);
        jdbc.update("UPDATE sc_timer_task SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE id=?", id);
        assertThat(timer.claimBatch("node-recovery", 1, LEASE)).isEmpty();
        assertThat(timerMapper.selectById(id).getStatus()).isEqualTo((short) 60);
        assertThat(timerMapper.selectById(id).getAttemptCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_execution", Integer.class)).isZero();
    }

    @Test
    @Order(1)
    void brokenActiveLeasesDoNotOccupyHealthyBatchAndDiagnosticsAreRateLimited() {
        var outboxLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OutboxDeliveryClaimService.class);
        var timerLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TimerClaimService.class);
        var messages = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        messages.start(); outboxLog.addAppender(messages); timerLog.addAppender(messages);
        try {
            for (int i = 0; i < 5; i++) {
                event((short) 10, 1, 3, "NULL", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)");
                task((short) 20, 1, 3, "NULL", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)");
            }
            Long healthyEvent = event((short) 0, 0, 3, "NULL", "CURRENT_TIMESTAMP(3)");
            Long healthyTask = task((short) 0, 0, 3, "NULL", "CURRENT_TIMESTAMP(3)");
            assertThat(outbox.claimBatch("healthy", 1, LEASE)).extracting(OutboxEventDO::getId).containsExactly(healthyEvent);
            assertThat(timer.claimBatch("healthy", 1, LEASE)).extracting(TimerClaimService.ClaimedTask::taskId).containsExactly(healthyTask);
            for (int i = 0; i < 20; i++) {
                assertThat(outbox.claimBatch("poll", 1, LEASE)).isEmpty();
                assertThat(timer.claimBatch("poll", 1, LEASE)).isEmpty();
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event WHERE lease_until IS NULL AND status=10 AND attempt_count=1 AND version=4", Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE lease_until IS NULL AND status=20 AND attempt_count=1 AND version=4", Integer.class)).isEqualTo(5);
            assertThat(messages.list.stream().filter(e -> e.getFormattedMessage().contains("CLAIM_ACTIVE_LEASE_MISSING")).toList())
                    .as("损坏租约应各有一条稳定诊断，不随高频扫描放大").hasSize(2)
                    .allSatisfy(e -> {
                        assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                        assertThat(e.getThrowableProxy()).isNull();
                    });
        } finally {
            outboxLog.detachAppender(messages); timerLog.detachAppender(messages); messages.stop();
        }
    }

    @Test
    void ordinaryDueRulesEqualityAndProtectedStatesUseRealDatabaseTime() {
        tx.executeWithoutResult(status -> {
            // MySQL会话时钟固定于整秒，让lease==库now成为确定性真实SQL边界，而非sleep近似。
            Long epoch = jdbc.queryForObject("SELECT UNIX_TIMESTAMP()", Long.class);
            jdbc.execute("SET timestamp=" + epoch);
            try {
                for (short state : new short[]{0, 30, 10, 20}) {
                    for (String leaseSql : state == 0 || state == 30
                            ? new String[]{"NULL", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)", "CURRENT_TIMESTAMP(3)"}
                            : new String[]{"CURRENT_TIMESTAMP(3)"}) {
                        Long eventId = event(state, 0, 2, leaseSql, "CURRENT_TIMESTAMP(3)");
                        Long taskId = task(state, 0, 2, leaseSql, "CURRENT_TIMESTAMP(3)");
                        assertThat(outbox.claimBatch("due", 1, LEASE)).extracting(OutboxEventDO::getId).containsExactly(eventId);
                        assertThat(timer.claimBatch("due", 1, LEASE)).extracting(TimerClaimService.ClaimedTask::taskId).containsExactly(taskId);
                        jdbc.update("DELETE FROM sc_outbox_event WHERE id=?", eventId);
                        jdbc.update("DELETE FROM sc_timer_task WHERE id=?", taskId);
                    }
                }
                for (short state : new short[]{0, 30}) {
                    event(state, 0, 2, "NULL", "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)");
                    task(state, 0, 2, "NULL", "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)");
                    event(state, 0, 2, "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)", "CURRENT_TIMESTAMP(3)");
                    task(state, 0, 2, "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)", "CURRENT_TIMESTAMP(3)");
                }
                for (short state : new short[]{10, 20}) {
                    event(state, 2, 2, "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)", "CURRENT_TIMESTAMP(3)");
                    task(state, 2, 2, "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)", "CURRENT_TIMESTAMP(3)");
                }
                for (short state : new short[]{40, 50, 90}) event(state, 2, 2, "CURRENT_TIMESTAMP(3)", "CURRENT_TIMESTAMP(3)");
                for (short state : new short[]{40, 50, 60, 90}) task(state, 2, 2, "CURRENT_TIMESTAMP(3)", "CURRENT_TIMESTAMP(3)");
                var eventsBefore = jdbc.queryForList("SELECT * FROM sc_outbox_event ORDER BY id");
                var tasksBefore = jdbc.queryForList("SELECT * FROM sc_timer_task ORDER BY id");
                assertThat(outbox.claimBatch("protected", 32, LEASE)).isEmpty();
                assertThat(timer.claimBatch("protected", 32, LEASE)).isEmpty();
                assertThat(jdbc.queryForList("SELECT * FROM sc_outbox_event ORDER BY id")).isEqualTo(eventsBefore);
                assertThat(jdbc.queryForList("SELECT * FROM sc_timer_task ORDER BY id")).isEqualTo(tasksBefore);
            } finally { jdbc.execute("SET timestamp=0"); }
        });
    }

    @Test
    void lockedCurrentRowAndClockReplaceScanSnapshotBeforeClaim() {
        for (boolean isTimer : new boolean[]{false, true}) {
            Long id = isTimer ? task((short) 0, 0, 2, "NULL", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)")
                    : event((short) 0, 0, 2, "NULL", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)");
            String table = isTimer ? "sc_timer_task" : "sc_outbox_event";
            String scan = isTimer ? ".selectClaimable" : ".selectDeliverable";
            tx.executeWithoutResult(status -> {
                long epoch = jdbc.queryForObject("SELECT UNIX_TIMESTAMP()", Long.class);
                jdbc.execute("SET timestamp=" + epoch);
                SQL.use(new DeterministicSqlInterceptor.Hook() {
                    @Override public void after(String statement, String sql, Object parameters) {
                        if (statement.endsWith(scan)) {
                            // 只执行真实SQL/会话时钟切换，不替换Mapper返回结果。
                            jdbc.update("UPDATE " + table + " SET version=version+1,attempt_count=1,payload_json='{\"updated\":true}' WHERE id=?", id);
                            jdbc.execute("SET timestamp=" + (epoch + 300));
                        }
                    }
                });
                try {
                    if (isTimer) {
                        var claims = timer.claimBatch("locked-now", 1, LEASE);
                        assertThat(claims).hasSize(1);
                        assertThat(claims.getFirst().attemptNo()).isEqualTo(2);
                        assertThat(claims.getFirst().payloadJson()).contains("updated");
                    } else {
                        var claims = outbox.claimBatch("locked-now", 1, LEASE);
                        assertThat(claims).hasSize(1);
                        assertThat(claims.getFirst().getAttemptCount()).isEqualTo(2);
                        assertThat(claims.getFirst().getPayloadJson()).contains("updated");
                    }
                    var row = jdbc.queryForMap("SELECT version,lease_until,updated_at FROM " + table + " WHERE id=?", id);
                    LocalDateTime lockedNow = LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(epoch + 300), java.time.ZoneOffset.ofHours(8));
                    assertThat(((Number) row.get("version")).longValue()).isEqualTo(6L);
                    assertThat(jdbc.queryForObject("SELECT lease_until FROM " + table + " WHERE id=?", LocalDateTime.class, id))
                            .isEqualTo(lockedNow.plus(LEASE));
                    assertThat(jdbc.queryForObject("SELECT updated_at FROM " + table + " WHERE id=?", LocalDateTime.class, id)).isEqualTo(lockedNow);
                } finally { SQL.reset(); jdbc.execute("SET timestamp=0"); }
            });
        }
    }

    @Test
    void casZeroNeverReturnsClaimAndCurrentInvalidationIsRechecked() {
        for (boolean isTimer : new boolean[]{false, true}) {
            for (boolean invalidateVersion : new boolean[]{false, true}) {
                Long id = isTimer ? task((short) 0, 0, 2, "NULL", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)")
                        : event((short) 0, 0, 2, "NULL", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)");
                String table = isTimer ? "sc_timer_task" : "sc_outbox_event";
                String scan = isTimer ? ".selectClaimable" : ".selectDeliverable";
                SQL.use(new DeterministicSqlInterceptor.Hook() {
                    @Override public void after(String statement, String sql, Object parameters) {
                        if (!invalidateVersion && statement.endsWith(scan)) {
                            jdbc.update("UPDATE " + table + " SET status=10,lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY) WHERE id=?", id);
                        }
                    }
                    @Override public void before(String statement, String sql, Object parameters) {
                        if (invalidateVersion && statement.endsWith(".update") && sql.contains("attempt_count = attempt_count + 1")) {
                            jdbc.update("UPDATE " + table + " SET version=version+1 WHERE id=?", id);
                        }
                    }
                });
                try {
                    if (isTimer) assertThat(timer.claimBatch("cas-zero", 1, LEASE)).isEmpty();
                    else assertThat(outbox.claimBatch("cas-zero", 1, LEASE)).isEmpty();
                    assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + table + " WHERE id=?", Integer.class, id)).isZero();
                    assertThat(jdbc.queryForObject("SELECT fencing_token FROM " + table + " WHERE id=?", Long.class, id)).isEqualTo(7L);
                } finally { SQL.reset(); }
                jdbc.update("DELETE FROM " + table + " WHERE id=?", id);
            }
        }
    }

    @Test
    void twoNodesSkipActualLockedRecoveryAndConsumeBudgetOnlyOnce() throws Exception {
        for (boolean isTimer : new boolean[]{false, true}) {
            Long id = isTimer ? task((short) 20, 0, 2, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)", "CURRENT_TIMESTAMP(3)")
                    : event((short) 20, 0, 2, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)", "CURRENT_TIMESTAMP(3)");
            String table = isTimer ? "sc_timer_task" : "sc_outbox_event";
            String scan = isTimer ? ".selectClaimable" : ".selectDeliverable";
            var locked = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            SQL.use(new DeterministicSqlInterceptor.Hook() {
                @Override public void after(String statement, String sql, Object parameters) throws Exception {
                    if (statement.endsWith(scan) && Thread.currentThread().getName().equals("b0-node-a")) {
                        locked.countDown(); DeterministicSqlInterceptor.await(release);
                    }
                }
            });
            try {
                var first = pool.submit(() -> {
                    Thread.currentThread().setName("b0-node-a");
                    return isTimer ? timer.claimBatch("node-a", 1, LEASE) : outbox.claimBatch("node-a", 1, LEASE);
                });
                DeterministicSqlInterceptor.await(locked);
                var probe = pool.submit(() -> tx.execute(status -> jdbc.queryForObject(
                        "SELECT id FROM " + table + " WHERE id=? FOR UPDATE", Long.class, id)));
                // 仅连接本轮Testcontainers：实际锁等待表证明A仍持锁、probe确实等待，不用sleep推断。
                try (var monitor = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
                    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                        try (var s = monitor.prepareStatement("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                                + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                                + "WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=?")) {
                            s.setString(1, MYSQL.getDatabaseName()); s.setString(2, table);
                            try (var rows = s.executeQuery()) {
                                assertThat(rows.next()).isTrue();
                                assertThat(rows.getInt(1)).as("真实阻塞锁等待").isPositive();
                            }
                        }
                    });
                }
                System.out.println("B0_LOCK_WAIT_EVIDENCE table=" + table + " blocking=node-a observed=true");
                if (isTimer) assertThat(timer.claimBatch("node-b", 1, LEASE)).isEmpty();
                else assertThat(outbox.claimBatch("node-b", 1, LEASE)).isEmpty();
                assertThat(probe.isDone()).isFalse();
                release.countDown();
                assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).hasSize(1);
                assertThat(probe.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(id);
                var row = jdbc.queryForMap("SELECT attempt_count,fencing_token,version,lease_owner FROM " + table + " WHERE id=?", id);
                assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
                assertThat(((Number) row.get("fencing_token")).longValue()).isEqualTo(8L);
                assertThat(((Number) row.get("version")).longValue()).isEqualTo(5L);
                assertThat(row.get("lease_owner")).isEqualTo("node-a");
            } finally {
                release.countDown(); SQL.reset(); pool.shutdown();
                assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void recoveryUsesCurrentReadDespiteOlderRepeatableReadView() throws Exception {
        for (boolean isTimer : new boolean[]{false, true}) {
            Long id = isTimer ? task((short) 20, 0, 3, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)", "CURRENT_TIMESTAMP(3)")
                    : event((short) 20, 0, 3, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)", "CURRENT_TIMESTAMP(3)");
            String table = isTimer ? "sc_timer_task" : "sc_outbox_event";
            var snapshot = new java.util.concurrent.CountDownLatch(1);
            var committed = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var claim = pool.submit(() -> tx.execute(status -> {
                    assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
                    assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + table + " WHERE id=?", Integer.class, id)).isZero();
                    snapshot.countDown();
                    awaitLatch(committed);
                    assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + table + " WHERE id=?", Integer.class, id)).isZero();
                    return isTimer ? timer.claimBatch("rr-current", 1, LEASE) : outbox.claimBatch("rr-current", 1, LEASE);
                }));
                DeterministicSqlInterceptor.await(snapshot);
                assertThat(jdbc.update("UPDATE " + table + " SET attempt_count=1,version=5 WHERE id=?", id)).isEqualTo(1);
                committed.countDown();
                assertThat(claim.get(20, java.util.concurrent.TimeUnit.SECONDS)).hasSize(1);
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + table + " WHERE id=?", Integer.class, id)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT version FROM " + table + " WHERE id=?", Long.class, id)).isEqualTo(6L);
            } finally {
                committed.countDown(); pool.shutdown();
                assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void recoveryPreservesOriginalOutboxAndTimerOrdering() {
        Long e1 = event((short) 0, 0, 3, "NULL", "'2020-01-02 00:00:00'");
        Long e2 = event((short) 30, 0, 3, "NULL", "'2020-01-01 00:00:00'");
        Long e3 = event((short) 0, 0, 3, "NULL", "'2020-01-01 00:00:00'");
        Long e4 = event((short) 20, 0, 3, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)", "'2999-01-01 00:00:00'");
        assertThat(outbox.claimBatch("ordered", 4, LEASE)).extracting(OutboxEventDO::getId).containsExactly(e2, e3, e1, e4);
        Long t1 = task((short) 0, 0, 3, "NULL", "'2020-01-02 00:00:00'");
        Long t2 = task((short) 30, 0, 3, "NULL", "'2020-01-01 00:00:00'");
        Long t3 = task((short) 0, 0, 3, "NULL", "'2020-01-01 00:00:00'");
        Long t4 = task((short) 20, 0, 3, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)", "'2999-01-01 00:00:00'");
        jdbc.update("UPDATE sc_timer_task SET priority=10 WHERE id IN (?,?)", t2, t3);
        jdbc.update("UPDATE sc_timer_task SET priority=20 WHERE id=?", t4);
        assertThat(timer.claimBatch("ordered", 4, LEASE)).extracting(TimerClaimService.ClaimedTask::taskId).containsExactly(t4, t2, t3, t1);
    }

    @Test
    void lockedBudgetExhaustionPreservesFuturePlanAndExistingAudit() {
        Long eventId = event((short) 20, 1, 2, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)", "'2999-01-01 00:00:00'");
        Long taskId = task((short) 20, 1, 2, "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)", "'2999-01-01 00:00:00'");
        // 隔离技术夹具预置先前审计，只证明扫描不得更改它，不冒称执行过Handler。
        jdbc.update("INSERT INTO sc_timer_execution(execution_no,task_id,attempt_no,fencing_token,worker_id,status,started_at,finished_at,created_at) "
                + "VALUES(?, ?,1,7,'fixture-old',30,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",
                UUID.randomUUID().toString().replace("-", ""), taskId);
        var audits = jdbc.queryForList("SELECT * FROM sc_timer_execution ORDER BY id");
        SQL.use(new DeterministicSqlInterceptor.Hook() {
            @Override public void after(String statement, String sql, Object parameters) {
                if (statement.endsWith(".selectDeliverable")) jdbc.update("UPDATE sc_outbox_event SET attempt_count=max_attempts,version=version+1 WHERE id=?", eventId);
                if (statement.endsWith(".selectClaimable")) jdbc.update("UPDATE sc_timer_task SET attempt_count=max_attempts,version=version+1 WHERE id=?", taskId);
            }
        });
        assertThat(outbox.claimBatch("locked-budget", 1, LEASE)).isEmpty();
        assertThat(timer.claimBatch("locked-budget", 1, LEASE)).isEmpty();
        assertThat(outboxMapper.selectById(eventId).getStatus()).isEqualTo((short) 50);
        assertThat(timerMapper.selectById(taskId).getStatus()).isEqualTo((short) 60);
        assertThat(outboxMapper.selectById(eventId).getVersion()).isEqualTo(6L);
        assertThat(timerMapper.selectById(taskId).getVersion()).isEqualTo(6L);
        assertThat(outboxMapper.selectById(eventId).getAvailableAt()).isEqualTo(LocalDateTime.of(2999, 1, 1, 0, 0));
        assertThat(timerMapper.selectById(taskId).getNextFireAt()).isEqualTo(LocalDateTime.of(2999, 1, 1, 0, 0));
        assertThat(jdbc.queryForList("SELECT * FROM sc_timer_execution ORDER BY id")).isEqualTo(audits);
    }

    @Test
    void realCheckFailurePreservesCauseAndRollsBackWholeClaimBatch() {
        for (boolean isTimer : new boolean[]{false, true}) {
            Long first = isTimer ? task((short) 0, 0, 2, "NULL", "'2020-01-01 00:00:00'")
                    : event((short) 0, 0, 2, "NULL", "'2020-01-01 00:00:00'");
            Long second = isTimer ? task((short) 0, 0, 2, "NULL", "'2020-01-02 00:00:00'")
                    : event((short) 0, 0, 2, "NULL", "'2020-01-02 00:00:00'");
            String table = isTimer ? "sc_timer_task" : "sc_outbox_event";
            var before = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id");
            var updates = new java.util.concurrent.atomic.AtomicInteger();
            SQL.use(new DeterministicSqlInterceptor.Hook() {
                @Override public void before(String statement, String sql, Object parameters) {
                    if (statement.endsWith(".update") && sql.contains("attempt_count = attempt_count + 1") && updates.incrementAndGet() == 2) {
                        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + table + " WHERE id=?", Integer.class, first)).isEqualTo(1);
                        // 第二条真实SQL触发最终CHECK，观察代理必须回滚已领取的第一行。
                        jdbc.update("UPDATE " + table + " SET attempt_count=max_attempts+1 WHERE id=?", second);
                    }
                }
            });
            Throwable failure;
            try {
                failure = org.assertj.core.api.Assertions.catchThrowable(() -> {
                    if (isTimer) timer.claimBatch("rollback", 2, LEASE);
                    else outbox.claimBatch("rollback", 2, LEASE);
                });
            } finally { SQL.reset(); }
            // SQL在真实MyBatis拦截器内触发，外层翻译为MyBatisSystemException，保留完整SQL因果链。
            assertThat(failure).isInstanceOf(org.mybatis.spring.MyBatisSystemException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            var sqlFailure = (java.sql.SQLException) root;
            assertThat(sqlFailure.getErrorCode()).isEqualTo(3819);
            assertThat(sqlFailure.getSQLState()).isEqualTo("HY000");
            assertThat(sqlFailure.getMessage()).contains(isTimer ? "ck_sc_timer_task_attempt" : "ck_sc_outbox_attempt");
            assertThat(updates.get()).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")).isEqualTo(before);
            jdbc.update("DELETE FROM " + table);
        }
    }

    private static void awaitLatch(java.util.concurrent.CountDownLatch latch) {
        try { DeterministicSqlInterceptor.await(latch); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("恢复夹具屏障中断", e);
        }
    }

    private static Long event(short state, int attempts, int max, String leaseSql, String planSql) {
        String key = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO sc_outbox_event(event_id,aggregate_type,aggregate_id,aggregate_version,"
                + "event_type,target_type,target_name,payload_version,payload_json,available_at,status,"
                + "attempt_count,max_attempts,lease_owner,lease_until,fencing_token,trace_id,last_error_code,"
                + "created_at,updated_at,version) VALUES(?,'B0_FIXTURE',?,1,'B0_FIXTURE','LOCAL','b0-test',"
                + "1,'{}'," + planSql + ",?,?,?,'node-old'," + leaseSql
                + ",7,'b0-trace','OLD_ERROR',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),4)", key, key, state, attempts, max);
        return jdbc.queryForObject("SELECT id FROM sc_outbox_event WHERE event_id=?", Long.class, key);
    }

    private static Long task(short state, int attempts, int max, String leaseSql, String planSql) {
        String key = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO sc_timer_task(task_no,task_type,idempotency_key,payload_version,payload_json,"
                + "schedule_type,next_fire_at,status,attempt_count,max_attempts,lease_owner,lease_until,"
                + "fencing_token,last_error_code,created_at,updated_at,version) VALUES(?,'B0_FIXTURE',?,1,'{}',"
                + "'ONCE'," + planSql + ",?,?,?,'node-old'," + leaseSql
                + ",7,'OLD_ERROR',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),4)", key, key, state, attempts, max);
        return jdbc.queryForObject("SELECT id FROM sc_timer_task WHERE task_no=?", Long.class, key);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxDeliveryClaimService.class, TimerClaimService.class})
    static class Fixture { }
}
