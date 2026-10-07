package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.timer.DatabasePersistentTimer;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandler;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
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
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.aifuli.module.supplychain.infrastructure.ledger.LedgerPostFlushWriteGuardInterceptor.isLockingStatement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * R3-03 §7.2.2(7) flush 后写守卫（C-POST）：锁定 SELECT 词法规则假阳 6 类/假阴 9 类负控制（纯词法判别）、
 * FLUSHED 上下文 holder 匹配时 Executor.update 与锁定 SELECT 执行前拒、FLUSHING 窗口放行、
 * 普通无锁 SELECT 放行、无收集上下文事务透明、同线程下一事务不拦截、链序互换负控制。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalLedgerIntentPostFlushGuardIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_post_flush_guard")
            .withCommand("--log-bin-trust-function-creators=1");
    private static ConfigurableApplicationContext context;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;
    private static LocalLedgerIntentCollector collector;
    private static ObjectMapper objectMapper;
    private static TimerTaskMapper timerTaskMapper;
    private static TimerTaskMapper reversedTimerTaskMapper;

    @BeforeAll
    static void start() throws Exception {
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
        timerTaskMapper = context.getBean(TimerTaskMapper.class);
        reversedTimerTaskMapper = reversedOrderFactory().openSession().getMapper(TimerTaskMapper.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    /** 互换链序负控制：MybatisPlusInterceptor 在前、写守卫在后，锁定语句仍拒、非锁定语句仍放行。 */
    private static SqlSessionFactory reversedOrderFactory() throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*.xml"));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factory.setPlugins(interceptor, new LedgerPostFlushWriteGuardInterceptor(dataSource));
        return factory.getObject();
    }

    // ==================== 词法假阳负控制（纯词法判别，不依赖 DB） ====================

    @Test @Order(1)
    void lexicalFalsePositivesAreReleased() {
        // 6 类假阳：字面量/行注释/块注释/反引号标识符内锁定文本、普通SELECT（含子查询无锁外层无锁）、别名含 update
        assertThat(isLockingStatement("SELECT 'FOR UPDATE' FROM t")).isFalse();
        assertThat(isLockingStatement("SELECT \"FOR UPDATE\" FROM t")).isFalse();
        assertThat(isLockingStatement("SELECT 1 -- FOR UPDATE")).isFalse();
        assertThat(isLockingStatement("SELECT 1 # FOR UPDATE")).isFalse();
        assertThat(isLockingStatement("SELECT 1 /* FOR UPDATE */")).isFalse();
        assertThat(isLockingStatement("SELECT `for update` FROM t")).isFalse();
        assertThat(isLockingStatement("SELECT u.update_time FROM t u")).isFalse();
        assertThat(isLockingStatement("SELECT * FROM t WHERE id IN (SELECT id FROM t2)")).isFalse();
        assertThat(isLockingStatement("SELECT 1")).isFalse();
        assertThat(isLockingStatement(null)).isFalse();
        assertThat(isLockingStatement("UPDATE t SET c=1 WHERE id=2")).isFalse();
    }

    @Test @Order(2)
    void lexicalFalseNegativesAreLocked() {
        // 9 类假阴：大小写/空白/后缀/FOR SHARE/LOCK IN SHARE MODE/子查询外层
        List<String> locked = List.of(
                "select * from t for update",
                "SELECT * FROM t For Update",
                "SELECT * FROM t FOR \n UPDATE",
                "SELECT * FROM t LOCK  IN   SHARE  MODE",
                "SELECT * FROM t FOR UPDATE SKIP LOCKED",
                "SELECT * FROM t FOR UPDATE OF c1, c2",
                "SELECT * FROM t FOR UPDATE NOWAIT",
                "SELECT * FROM t FOR SHARE",
                "SELECT * FROM t FOR SHARE OF c1",
                "SELECT * FROM t LOCK IN SHARE MODE",
                "SELECT * FROM t ORDER BY id LIMIT 1 FOR UPDATE",
                "SELECT * FROM t WHERE id IN (SELECT id FROM t2) ORDER BY id FOR UPDATE",
                "SELECT * FROM t FOR UPDATE OF c1 NOWAIT",
                "SELECT * FROM t FOR SHARE SKIP LOCKED");
        for (String sql : locked) {
            assertThat(isLockingStatement(sql)).as(sql).isTrue();
        }
    }

    // ==================== 真实事务守卫行为 ====================

    @Test @Order(3)
    void flushThenDomainInsertRejected() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "guard-insert", objectMapper.createObjectNode().put("g", 1),
                        Instant.now().plusSeconds(60)));
                collector.flush();
                timerTaskMapper.insert(newTask("guard-insert-domain"));
                return null;
            });
            throw new AssertionError("flush 后领域 INSERT 必须被拒");
        } catch (RuntimeException expected) {
            assertFlushGuardRejection(expected, "写语句");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key IN ('guard-insert','guard-insert-domain')",
                Integer.class);
        assertThat(count).as("守卫拒绝后整事务回滚").isZero();
    }

    @Test @Order(4)
    void flushThenDomainUpdateAndDeleteRejected() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long domainId = tx.execute(status -> {
            TimerTaskDO domain = newTask("guard-update-domain");
            timerTaskMapper.insert(domain);
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "guard-update", objectMapper.createObjectNode().put("g", 1),
                    Instant.now().plusSeconds(60)));
            collector.flush();
            try {
                timerTaskMapper.updateById(domain);
                throw new AssertionError("flush 后领域 UPDATE 必须被拒");
            } catch (RuntimeException expected) {
                assertFlushGuardRejection(expected, "写语句");
            }
            try {
                timerTaskMapper.deleteById(domain.getId());
                throw new AssertionError("flush 后领域 DELETE 必须被拒");
            } catch (RuntimeException expected) {
                assertFlushGuardRejection(expected, "写语句");
            }
            return domain.getId();
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE id = ?", Integer.class, domainId);
        assertThat(count).as("flush 前领域写合法提交，flush 后 UPDATE/DELETE 被拒").isEqualTo(1);
    }

    @Test @Order(5)
    void flushThenLockingSelectRejected() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.execute(status -> {
                collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                        "TEST_ONCE", "guard-lock-select", objectMapper.createObjectNode().put("g", 1),
                        Instant.now().plusSeconds(60)));
                collector.flush();
                timerTaskMapper.selectClaimable(LocalDateTime.now(), 10);
                return null;
            });
            throw new AssertionError("flush 后锁定 SELECT 必须被拒");
        } catch (RuntimeException expected) {
            assertFlushGuardRejection(expected, "锁定SELECT");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='guard-lock-select'", Integer.class);
        assertThat(count).isZero();
    }

    @Test @Order(6)
    void flushThenPlainSelectAllowed() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "guard-plain-select", objectMapper.createObjectNode().put("g", 1),
                    Instant.now().plusSeconds(60)));
            collector.flush();
            // 负控制：普通无锁 SELECT 放行
            List<Long> broken = timerTaskMapper.selectBrokenActiveLeaseIds(10);
            assertThat(broken).isNotNull();
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='guard-plain-select'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test @Order(7)
    void noCollectorContextTransactionIsTransparent() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            timerTaskMapper.insert(newTask("guard-transparent-1"));
            timerTaskMapper.selectClaimable(LocalDateTime.now(), 10);
            timerTaskMapper.selectBrokenActiveLeaseIds(10);
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='guard-transparent-1'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test @Order(8)
    void nextTransactionOnSameThreadNotBlocked() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "guard-next-tx", objectMapper.createObjectNode().put("g", 1),
                    Instant.now().plusSeconds(60)));
            collector.flush();
            return null;
        });
        TransactionTemplate next = new TransactionTemplate(txManager);
        next.execute(status -> {
            timerTaskMapper.insert(newTask("guard-next-tx-domain"));
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='guard-next-tx-domain'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test @Order(9)
    void flushingWindowAllowsSlotWrites() {
        // FLUSHING 窗口放行仅覆盖 flush 路径固定代码（分组 SQL＋scheduleOnce 写入）：flush 成功即证据
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "guard-flushing-window", objectMapper.createObjectNode().put("g", 1),
                    Instant.now().plusSeconds(60)));
            collector.flush();
            return null;
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_task WHERE idempotency_key='guard-flushing-window'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test @Order(10)
    void reversedChainOrderStillGuards() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            collector.recordTimerIntent(new LocalLedgerIntentCollector.TimerIntent(
                    "TEST_ONCE", "guard-chain-order", objectMapper.createObjectNode().put("g", 1),
                    Instant.now().plusSeconds(60)));
            collector.flush();
            // 互换链序：锁定语句仍拒、非锁定语句仍放行
            Throwable rejection = catchThrowable(() -> reversedTimerTaskMapper.selectClaimable(LocalDateTime.now(), 10));
            assertThat(rejection).as("链序互换后锁定 SELECT 必须被拒").isNotNull();
            assertFlushGuardRejection(rejection, "锁定SELECT");
            assertThat(reversedTimerTaskMapper.selectBrokenActiveLeaseIds(10)).isNotNull();
            return null;
        });
    }

    private static TimerTaskDO newTask(String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now();
        return TimerTaskDO.builder()
                .taskNo("TMR_GUARD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18))
                .taskType("MARKER")
                .idempotencyKey(idempotencyKey)
                .payloadVersion(1)
                .payloadJson("{\"g\":1}")
                .scheduleType("ONCE")
                .nextFireAt(now.plusMinutes(10))
                .priority(0)
                .shard(0)
                .status((short) 0)
                .attemptCount(0)
                .maxAttempts(3)
                .fencingToken(0L)
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build();
    }

    /**
     * MyBatis/MyBatis-Spring 包装拦截器异常为 PersistenceException/MyBatisSystemException，
     * 守卫 SIE 常仅以 cause 文本并入外层 message，须走 cause 链 + message 文本双通道；
     * 双通道均要求同时命中守卫类型标记与业务消息片段，避免普通异常消息假绿。
     */
    private static void assertFlushGuardRejection(Throwable failure, String messagePart) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SystemIntegrityException integrity
                    && integrity.getMessage() != null
                    && integrity.getMessage().contains(messagePart)) {
                return;
            }
            current = current.getCause();
        }
        String message = failure.getMessage();
        if (message != null
                && message.contains("SystemIntegrityException")
                && message.contains(messagePart)) {
            return;
        }
        throw new AssertionError("异常未包含 flush 后写守卫拒绝: " + messagePart, failure);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            SupplyChainDataSourceConfiguration.class,
            BusinessNoGenerator.class,
            UlidGenerator.class,
            DefaultAppendScheduleIdentityLedgerService.class,
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
