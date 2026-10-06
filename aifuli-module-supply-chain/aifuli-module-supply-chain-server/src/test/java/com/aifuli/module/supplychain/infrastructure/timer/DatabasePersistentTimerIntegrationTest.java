package com.aifuli.module.supplychain.infrastructure.timer;

import com.aifuli.module.supplychain.infrastructure.ledger.SystemIntegrityException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.DefaultAppendScheduleIdentityLedgerService;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3-02T 真实 Timer 调度与账本绑定集成测试。
 * 依据设计规范：
 * docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#81-真实-timer-调度与账本绑定实现r3-02t
 * 验证：
 * 1. 首次调度：账本排他建槽 + 严格源任务 INSERT 1 + bindTimerTask CAS 绑定同事务提交，返回真实 (taskNo, taskId, 0L)；
 * 2. 重复调度：命中有且仅有账本已绑定信息，对 sc_timer_task 达到零 SELECT / 零 INSERT / 零 UPDATE / 零行锁；
 * 3. 载荷冲突：异 payload 抛出 SystemIntegrityException 且源表零写入；
 * 4. 故障注入：源任务插入失败整事务回滚，无孤儿槽、无孤儿任务；
 * 5. 终态任务：终态旧任务重复调度不重开，状态不被重置。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabasePersistentTimerIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_timer_ledger")
            .withCommand("--log-bin-trust-function-creators=1");
    private static ConfigurableApplicationContext context;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static PersistentTimer timer;
    private static SqlProbe probe;
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
        probe = context.getBean(SqlProbe.class);
        context.getBean("supplyChainSqlSessionFactory", SqlSessionFactory.class).getConfiguration().addInterceptor(probe);
        timer = context.getBean(PersistentTimer.class);
        objectMapper = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    @Test @Order(1)
    void scheduleOncePersistsTaskAndBindsIdentityLedgerSlot() {
        probe.calls.clear();
        ObjectNode payload = objectMapper.createObjectNode().put("value", 100);
        TimerHandle handle = timer.scheduleOnce("TEST_ONCE", "key-first", payload, Instant.now().plusSeconds(60));

        assertThat(handle).isNotNull();
        assertThat(handle.taskNo()).startsWith("TMR_");
        assertThat(handle.taskId()).isNotNull().isPositive();
        assertThat(handle.fencingToken()).isEqualTo(0L);

        // 验证 sc_timer_task 真实源表落盘
        Integer taskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE id=? AND task_no=? AND status=0 AND fencing_token=0",
                Integer.class, handle.taskId(), handle.taskNo());
        assertThat(taskCount).isEqualTo(1);

        // 验证 sc_timer_schedule_identity 身份账本排他建槽并成功绑定（STATUS_BOUND = 20）
        Map<String, Object> ledgerRow = jdbc.queryForMap(
                "SELECT binding_status, task_id, task_no, reservation_nonce FROM sc_timer_schedule_identity "
                        + "WHERE task_type='TEST_ONCE' AND idempotency_key='key-first'");
        assertThat(((Number) ledgerRow.get("binding_status")).intValue()).isEqualTo(20);
        assertThat(((Number) ledgerRow.get("task_id")).longValue()).isEqualTo(handle.taskId());
        assertThat(ledgerRow.get("task_no")).isEqualTo(handle.taskNo());
        assertThat(ledgerRow.get("reservation_nonce")).isNotNull();
    }

    @Test @Order(2)
    void duplicateScheduleOnceReturnsExistingHandleWithZeroSourceSqlAndZeroLock() {
        ObjectNode payload = objectMapper.createObjectNode().put("value", 200);
        TimerHandle first = timer.scheduleOnce("TEST_ONCE", "key-dup", payload, Instant.now().plusSeconds(60));
        assertThat(first).isNotNull();

        probe.calls.clear();
        TimerHandle second = timer.scheduleOnce("TEST_ONCE", "key-dup", payload, Instant.now().plusSeconds(120));

        assertThat(second).isNotNull();
        assertThat(second.taskNo()).isEqualTo(first.taskNo());
        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(second.fencingToken()).isEqualTo(0L);

        // 验证重复调用时对源表 sc_timer_task 零 SELECT / 零 INSERT / 零 UPDATE / 零行锁
        long timerTaskSqlCalls = probe.calls.stream()
                .filter(c -> c.id.contains("TimerTaskMapper"))
                .count();
        assertThat(timerTaskSqlCalls).as("重复调度命中账本时，对源表 sc_timer_task 必须为零调用").isZero();

        // 验证账本表命中（排他建槽 upsert + selectForUpdate）
        long ledgerSqlCalls = probe.calls.stream()
                .filter(c -> c.id.contains("TimerScheduleIdentityMapper"))
                .count();
        assertThat(ledgerSqlCalls).as("重复调度仅查询与判定账本槽").isEqualTo(2);
    }

    @Test @Order(3)
    void conflictingPayloadThrowsSystemIntegrityExceptionAndLeavesSourceUntouched() {
        ObjectNode payload1 = objectMapper.createObjectNode().put("orderId", 1L);
        TimerHandle first = timer.scheduleOnce("TEST_ONCE", "key-conflict", payload1, Instant.now().plusSeconds(60));
        assertThat(first).isNotNull();

        ObjectNode payload2 = objectMapper.createObjectNode().put("orderId", 2L);
        assertThatThrownBy(() -> timer.scheduleOnce("TEST_ONCE", "key-conflict", payload2, Instant.now().plusSeconds(60)))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("载荷协议或内容冲突");

        // 验证源表 sc_timer_task 依然仅存在第一笔数据，未被破坏或修改
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' AND idempotency_key='key-conflict'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test @Order(4)
    void sourceInsertFailureRollsBackLedgerSlotWithoutOrphans() {
        // 创建触发器模拟源表插入失败（如业务或存储异常）
        jdbc.execute("CREATE TRIGGER trg_test_timer_fail BEFORE INSERT ON sc_timer_task "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SIMULATED_TIMER_INSERT_FAILURE'");
        try {
            ObjectNode payload = objectMapper.createObjectNode().put("fault", true);
            assertThatThrownBy(() -> timer.scheduleOnce("TEST_ONCE", "key-fail", payload, Instant.now().plusSeconds(60)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_timer_fail");
        }

        // 验证整事务回滚：源表零任务，账本表零槽，无任何孤儿行
        Integer taskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE task_type='TEST_ONCE' AND idempotency_key='key-fail'",
                Integer.class);
        assertThat(taskCount).as("源任务表不应有孤儿行").isZero();

        Integer ledgerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_schedule_identity WHERE task_type='TEST_ONCE' AND idempotency_key='key-fail'",
                Integer.class);
        assertThat(ledgerCount).as("身份账本表不应有孤儿槽").isZero();
    }

    @Test @Order(5)
    void terminalStatusTaskDuplicateReturnsExistingHandleWithoutReopen() {
        ObjectNode payload = objectMapper.createObjectNode().put("terminal", true);
        TimerHandle first = timer.scheduleOnce("TEST_ONCE", "key-term", payload, Instant.now().plusSeconds(60));
        assertThat(first).isNotNull();

        // 手工将源任务状态更新为终态 COMPLETED (10)
        jdbc.update("UPDATE sc_timer_task SET status=10 WHERE id=?", first.taskId());

        // 重复调度该任务
        TimerHandle second = timer.scheduleOnce("TEST_ONCE", "key-term", payload, Instant.now().plusSeconds(120));
        assertThat(second.taskNo()).isEqualTo(first.taskNo());
        assertThat(second.taskId()).isEqualTo(first.taskId());

        // 验证源任务状态保持 COMPLETED (10)，未被重开为 0
        Integer status = jdbc.queryForObject("SELECT status FROM sc_timer_task WHERE id=?", Integer.class, first.taskId());
        assertThat(status).as("终态任务不应自动重开").isEqualTo(10);
    }

    record Call(String id, Object result, Connection connection, String sql) {}

    @Intercepts({
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    public static class SqlProbe implements Interceptor {
        final List<Call> calls = new ArrayList<>();
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Connection connection = ((Executor) invocation.getTarget()).getTransaction().getConnection();
            Object result = invocation.proceed();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            calls.add(new Call(statement.getId(), result, connection, statement.getBoundSql(invocation.getArgs()[1]).getSql()));
            return result;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            SupplyChainDataSourceConfiguration.class,
            BusinessNoGenerator.class,
            UlidGenerator.class,
            DefaultAppendScheduleIdentityLedgerService.class,
            DatabasePersistentTimer.class,
            PersistentTimerHandlerRegistry.class
    })
    static class Fixture {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean SqlProbe sqlProbe() { return new SqlProbe(); }
        @Bean List<PersistentTimerHandler> dummyHandlers() {
            return List.of(new PersistentTimerHandler() {
                @Override public String taskType() { return "TEST_ONCE"; }
                @Override public int maxAttempts() { return 3; }
                @Override public String handle(TimerTaskContext context, com.fasterxml.jackson.databind.JsonNode payload) {
                    return "SUCCESS";
                }
            });
        }
    }
}
