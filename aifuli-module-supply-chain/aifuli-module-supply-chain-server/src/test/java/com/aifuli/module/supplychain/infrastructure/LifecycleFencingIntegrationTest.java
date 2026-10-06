package com.aifuli.module.supplychain.infrastructure;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxDeliveryClaimService;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventDO;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.TimerClaimService;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerExecutionDO;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerExecutionMapper;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper;
import org.junit.jupiter.api.*;
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
 * B0切片2：全生命周期转换、Fencing失格校验与Timer执行审计对齐集成测试。
 * 设计依据：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#32-转换表
 * 与 #33-timer审计与失败处理。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LifecycleFencingIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_lifecycle_fencing");
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static OutboxDeliveryClaimService outbox;
    private static TimerClaimService timer;
    private static OutboxEventMapper outboxMapper;
    private static TimerTaskMapper timerMapper;
    private static TimerExecutionMapper timerExecutionMapper;
    private static SupplyChainDatabaseClock databaseClock;
    private static TransactionTemplate tx;

    private static final Duration LEASE = Duration.ofSeconds(30);

    @BeforeAll
    static void start() {
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
        timerExecutionMapper = context.getBean(TimerExecutionMapper.class);
        databaseClock = context.getBean(SupplyChainDatabaseClock.class);
        tx = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM sc_timer_execution");
        jdbc.update("DELETE FROM sc_timer_task");
        jdbc.update("DELETE FROM sc_outbox_event");
    }

    // ==========================================
    // Cycle 1: Outbox markDelivering, publish, renewLease
    // ==========================================

    @Test
    @Order(1)
    void outboxMarkDeliveringEnforcesClaimedStatusOwnerFencingAndValidLease() {
        // 1. 正常有效行：CLAIMED，匹配worker与fencing，租约有效 -> 允许迁往 DELIVERING
        Long validId = event(OutboxEventMapper.STATUS_CLAIMED, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.markDelivering(validId, 1L, "node-a");
        OutboxEventDO validEv = outboxMapper.selectById(validId);
        assertThat(validEv.getStatus()).as("正常前态与未过期租约更新为DELIVERING")
                .isEqualTo(OutboxEventMapper.STATUS_DELIVERING);
        assertThat(validEv.getVersion()).as("版本递增").isEqualTo(1L);

        // 2. 过期租约：lease_until <= now -> 失格，0行更新，状态不变
        Long expiredId = event(OutboxEventMapper.STATUS_CLAIMED, 1, 3,
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.markDelivering(expiredId, 1L, "node-a");
        OutboxEventDO expiredEv = outboxMapper.selectById(expiredId);
        assertThat(expiredEv.getStatus()).as("过期租约不得转DELIVERING，保持CLAIMED")
                .isEqualTo(OutboxEventMapper.STATUS_CLAIMED);
        assertThat(expiredEv.getVersion()).as("过期租约版本不递增").isEqualTo(0L);

        // 3. FencingToken 不匹配 -> 失格，0行更新，状态不变
        Long wrongTokenId = event(OutboxEventMapper.STATUS_CLAIMED, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.markDelivering(wrongTokenId, 99L, "node-a");
        OutboxEventDO wrongTokenEv = outboxMapper.selectById(wrongTokenId);
        assertThat(wrongTokenEv.getStatus()).as("FencingToken不匹配不得更新").isEqualTo(OutboxEventMapper.STATUS_CLAIMED);
        assertThat(wrongTokenEv.getVersion()).isEqualTo(0L);

        // 4. WorkerId 不匹配 -> 失格，0行更新，状态不变
        Long wrongWorkerId = event(OutboxEventMapper.STATUS_CLAIMED, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.markDelivering(wrongWorkerId, 1L, "node-b");
        OutboxEventDO wrongWorkerEv = outboxMapper.selectById(wrongWorkerId);
        assertThat(wrongWorkerEv.getStatus()).as("WorkerId不匹配不得更新").isEqualTo(OutboxEventMapper.STATUS_CLAIMED);
        assertThat(wrongWorkerEv.getVersion()).isEqualTo(0L);

        // 5. 非法前态（如 PENDING）-> 失格，0行更新，状态不变
        Long pendingId = event(OutboxEventMapper.STATUS_PENDING, 0, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.markDelivering(pendingId, 1L, "node-a");
        OutboxEventDO pendingEv = outboxMapper.selectById(pendingId);
        assertThat(pendingEv.getStatus()).as("非CLAIMED前态不得更新为DELIVERING").isEqualTo(OutboxEventMapper.STATUS_PENDING);
        assertThat(pendingEv.getVersion()).isEqualTo(0L);
    }

    @Test
    @Order(2)
    void outboxPublishEnforcesClaimedOrDeliveringOwnerFencingAndValidLease() {
        // 1. 正常有效行（DELIVERING）：匹配worker与fencing，租约有效 -> 允许迁往 PUBLISHED 并清空租约
        Long deliveringId = event(OutboxEventMapper.STATUS_DELIVERING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.publish(deliveringId, 1L, "node-a");
        OutboxEventDO pubEv = outboxMapper.selectById(deliveringId);
        assertThat(pubEv.getStatus()).as("正常DELIVERING更新为PUBLISHED").isEqualTo(OutboxEventMapper.STATUS_PUBLISHED);
        assertThat(pubEv.getLeaseOwner()).as("清空leaseOwner").isNull();
        assertThat(pubEv.getLeaseUntil()).as("清空leaseUntil").isNull();
        assertThat(pubEv.getPublishedAt()).as("记录publishedAt").isNotNull();
        assertThat(pubEv.getVersion()).isEqualTo(1L);

        // 2. 过期租约：lease_until <= now -> 失格，0行更新，状态不转PUBLISHED
        Long expiredId = event(OutboxEventMapper.STATUS_DELIVERING, 1, 3,
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.publish(expiredId, 1L, "node-a");
        OutboxEventDO expiredEv = outboxMapper.selectById(expiredId);
        assertThat(expiredEv.getStatus()).as("过期租约不得PUBLISHED").isEqualTo(OutboxEventMapper.STATUS_DELIVERING);
        assertThat(expiredEv.getVersion()).isEqualTo(0L);

        // 3. FencingToken 不匹配 -> 失格
        Long wrongTokenId = event(OutboxEventMapper.STATUS_DELIVERING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.publish(wrongTokenId, 99L, "node-a");
        assertThat(outboxMapper.selectById(wrongTokenId).getStatus()).isEqualTo(OutboxEventMapper.STATUS_DELIVERING);

        // 4. 终态不可再转 PUBLISHED
        Long deadId = event(OutboxEventMapper.STATUS_DEAD, 3, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.publish(deadId, 1L, "node-a");
        assertThat(outboxMapper.selectById(deadId).getStatus()).isEqualTo(OutboxEventMapper.STATUS_DEAD);
    }

    @Test
    @Order(3)
    void outboxRenewLeaseRejectsExpiredLeaseAndNeverRevives() {
        // 1. 正常有效租约：延期成功
        Long validId = event(OutboxEventMapper.STATUS_DELIVERING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 10 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        LocalDateTime before = outboxMapper.selectById(validId).getLeaseUntil();
        outbox.renewLease(validId, 1L, "node-a", Duration.ofSeconds(60));
        OutboxEventDO renewed = outboxMapper.selectById(validId);
        assertThat(renewed.getLeaseUntil()).as("有效租约延期成功").isAfter(before);
        assertThat(renewed.getVersion()).isEqualTo(1L);

        // 2. 已过期租约：lease_until <= now -> 严禁到期后复活，更新0行
        Long expiredId = event(OutboxEventMapper.STATUS_DELIVERING, 1, 3,
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        LocalDateTime expiredBefore = outboxMapper.selectById(expiredId).getLeaseUntil();
        outbox.renewLease(expiredId, 1L, "node-a", Duration.ofSeconds(60));
        OutboxEventDO notRenewed = outboxMapper.selectById(expiredId);
        assertThat(notRenewed.getLeaseUntil()).as("已过期租约不可复活").isEqualTo(expiredBefore);
        assertThat(notRenewed.getVersion()).isEqualTo(0L);

        // 3. FencingToken 不匹配 -> 续租失败
        Long wrongTokenId = event(OutboxEventMapper.STATUS_DELIVERING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 10 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        LocalDateTime tokenBefore = outboxMapper.selectById(wrongTokenId).getLeaseUntil();
        outbox.renewLease(wrongTokenId, 99L, "node-a", Duration.ofSeconds(60));
        assertThat(outboxMapper.selectById(wrongTokenId).getLeaseUntil()).isEqualTo(tokenBefore);
        assertThat(outboxMapper.selectById(wrongTokenId).getVersion()).isEqualTo(0L);
    }

    // ==========================================
    // Cycle 2: Outbox fail and dead-letter available_at protection
    // ==========================================

    @Test
    @Order(4)
    void outboxFailDeadLetterPreservesNonEmptyAvailableAtAndClearsLease() {
        // 1. 死信失败：deadLetter=true -> 状态迁DEAD(50)，清空租约，available_at保留非空原计划时间（绝不为NULL）
        Long deadId = event(OutboxEventMapper.STATUS_DELIVERING, 3, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        LocalDateTime originalAvailableAt = outboxMapper.selectById(deadId).getAvailableAt();
        assertThat(originalAvailableAt).as("初始available_at非空").isNotNull();

        outbox.fail(deadId, 1L, "node-a", "EXCEEDED_MAX_ATTEMPTS", Duration.ofSeconds(10), true);

        OutboxEventDO deadEv = outboxMapper.selectById(deadId);
        assertThat(deadEv.getStatus()).as("状态变为DEAD").isEqualTo(OutboxEventMapper.STATUS_DEAD);
        assertThat(deadEv.getLeaseOwner()).as("清空leaseOwner").isNull();
        assertThat(deadEv.getLeaseUntil()).as("清空leaseUntil").isNull();
        assertThat(deadEv.getLastErrorCode()).as("记录失败原因码").isEqualTo("EXCEEDED_MAX_ATTEMPTS");
        assertThat(deadEv.getAvailableAt()).as("死信保留非空available_at，绝不写NULL")
                .isNotNull()
                .isEqualTo(originalAvailableAt);
        assertThat(deadEv.getVersion()).isEqualTo(1L);

        // 2. 普通重试失败：deadLetter=false -> 状态迁RETRY(30)，清空租约，设置nextAvailableAt
        Long retryId = event(OutboxEventMapper.STATUS_CLAIMED, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        LocalDateTime beforeAvailable = outboxMapper.selectById(retryId).getAvailableAt();

        outbox.fail(retryId, 1L, "node-a", "TEMP_TIMEOUT", Duration.ofSeconds(10), false);

        OutboxEventDO retryEv = outboxMapper.selectById(retryId);
        assertThat(retryEv.getStatus()).as("状态变为RETRY").isEqualTo(OutboxEventMapper.STATUS_RETRY);
        assertThat(retryEv.getLeaseOwner()).as("清空leaseOwner").isNull();
        assertThat(retryEv.getLeaseUntil()).as("清空leaseUntil").isNull();
        assertThat(retryEv.getLastErrorCode()).as("记录失败原因码").isEqualTo("TEMP_TIMEOUT");
        assertThat(retryEv.getAvailableAt()).as("普通失败更新nextAvailableAt").isAfter(beforeAvailable);
        assertThat(retryEv.getVersion()).isEqualTo(1L);
    }

    @Test
    @Order(5)
    void outboxFailRejectsExpiredLeaseOrMismatchedFencing() {
        // 1. 已过期租约：lease_until <= now -> 失格，0行更新，状态不变
        Long expiredId = event(OutboxEventMapper.STATUS_DELIVERING, 1, 3,
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.fail(expiredId, 1L, "node-a", "SOME_ERR", Duration.ofSeconds(10), false);
        OutboxEventDO expiredEv = outboxMapper.selectById(expiredId);
        assertThat(expiredEv.getStatus()).as("过期租约不得fail更新状态").isEqualTo(OutboxEventMapper.STATUS_DELIVERING);
        assertThat(expiredEv.getVersion()).isEqualTo(0L);

        // 2. FencingToken 不匹配 -> 失格，0行更新
        Long wrongTokenId = event(OutboxEventMapper.STATUS_DELIVERING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.fail(wrongTokenId, 99L, "node-a", "SOME_ERR", Duration.ofSeconds(10), false);
        assertThat(outboxMapper.selectById(wrongTokenId).getStatus()).isEqualTo(OutboxEventMapper.STATUS_DELIVERING);
        assertThat(outboxMapper.selectById(wrongTokenId).getVersion()).isEqualTo(0L);

        // 3. WorkerId 不匹配 -> 失格，0行更新
        Long wrongWorkerId = event(OutboxEventMapper.STATUS_DELIVERING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        outbox.fail(wrongWorkerId, 1L, "node-other", "SOME_ERR", Duration.ofSeconds(10), false);
        assertThat(outboxMapper.selectById(wrongWorkerId).getStatus()).isEqualTo(OutboxEventMapper.STATUS_DELIVERING);
        assertThat(outboxMapper.selectById(wrongWorkerId).getVersion()).isEqualTo(0L);
    }

    // ==========================================
    // Cycle 3: Timer markExecuting, complete, renewLease and success execution audit
    // ==========================================

    @Test
    @Order(6)
    void timerMarkExecutingEnforcesClaimedStatusOwnerFencingAndValidLease() {
        // 1. 正常CLAIMED行：租约有效、匹配worker与fencing -> 迁往 EXECUTING
        Long validId = task(TimerTaskMapper.STATUS_CLAIMED, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        TimerClaimService.ClaimedTask validTask = new TimerClaimService.ClaimedTask(validId, "TMR_V1", "B0_FIXTURE", 1, 1L, "{}");
        timer.startExecution(validTask, "node-a");

        TimerTaskDO validDO = timerMapper.selectById(validId);
        assertThat(validDO.getStatus()).as("正常前态与未过期租约转为EXECUTING").isEqualTo(TimerTaskMapper.STATUS_EXECUTING);
        assertThat(validDO.getVersion()).isEqualTo(1L);

        // 2. 过期租约：lease_until <= now -> 失格，0行更新，保持CLAIMED
        Long expiredId = task(TimerTaskMapper.STATUS_CLAIMED, 1, 3,
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        TimerClaimService.ClaimedTask expiredTask = new TimerClaimService.ClaimedTask(expiredId, "TMR_EX", "B0_FIXTURE", 1, 1L, "{}");
        timer.startExecution(expiredTask, "node-a");

        TimerTaskDO expiredDO = timerMapper.selectById(expiredId);
        assertThat(expiredDO.getStatus()).as("过期租约不得转EXECUTING").isEqualTo(TimerTaskMapper.STATUS_CLAIMED);
        assertThat(expiredDO.getVersion()).isEqualTo(0L);

        // 3. FencingToken 不匹配 -> 失格
        Long wrongTokenId = task(TimerTaskMapper.STATUS_CLAIMED, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        TimerClaimService.ClaimedTask wrongTokenTask = new TimerClaimService.ClaimedTask(wrongTokenId, "TMR_WT", "B0_FIXTURE", 1, 99L, "{}");
        timer.startExecution(wrongTokenTask, "node-a");
        assertThat(timerMapper.selectById(wrongTokenId).getStatus()).isEqualTo(TimerTaskMapper.STATUS_CLAIMED);
        assertThat(timerMapper.selectById(wrongTokenId).getVersion()).isEqualTo(0L);
    }

    @Test
    @Order(7)
    void timerRenewLeaseRejectsExpiredLeaseAndNeverRevives() {
        // 1. 正常有效租约：延期成功
        Long validId = task(TimerTaskMapper.STATUS_EXECUTING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 10 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        LocalDateTime before = timerMapper.selectById(validId).getLeaseUntil();
        timer.renewLease(validId, 1L, "node-a", Duration.ofSeconds(60));
        TimerTaskDO renewed = timerMapper.selectById(validId);
        assertThat(renewed.getLeaseUntil()).as("有效租约延期成功").isAfter(before);
        assertThat(renewed.getVersion()).isEqualTo(1L);

        // 2. 已过期租约：lease_until <= now -> 严禁到期后复活，更新0行
        Long expiredId = task(TimerTaskMapper.STATUS_EXECUTING, 1, 3,
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        LocalDateTime expiredBefore = timerMapper.selectById(expiredId).getLeaseUntil();
        timer.renewLease(expiredId, 1L, "node-a", Duration.ofSeconds(60));
        TimerTaskDO after = timerMapper.selectById(expiredId);
        assertThat(after.getLeaseUntil()).as("已过期租约不可复活").isEqualTo(expiredBefore);
        assertThat(after.getVersion()).isEqualTo(0L);
    }

    @Test
    @Order(8)
    void timerCompleteRecordsSuccessExecutionOnlyWhenExactlyOneRowUpdated() {
        byte[] resultDigest = new byte[32];
        resultDigest[0] = 1;
        resultDigest[1] = 2;
        resultDigest[31] = 9;
        LocalDateTime startedAt = databaseClock.now().minusSeconds(2);

        // 1. 正常有效任务：EXECUTING，有效租约 -> complete成功且同事务记录STATUS_EXEC_SUCCESS审计
        Long validId = task(TimerTaskMapper.STATUS_EXECUTING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        TimerClaimService.ClaimedTask validTask = new TimerClaimService.ClaimedTask(validId, "TMR_CP_V", "B0_FIXTURE", 1, 1L, "{}");
        timer.complete(validTask, "node-a", startedAt, "SUCCESS", resultDigest);

        TimerTaskDO completedDO = timerMapper.selectById(validId);
        assertThat(completedDO.getStatus()).as("状态变为COMPLETED").isEqualTo(TimerTaskMapper.STATUS_COMPLETED);
        assertThat(completedDO.getLeaseOwner()).as("清空leaseOwner").isNull();
        assertThat(completedDO.getLeaseUntil()).as("清空leaseUntil").isNull();
        assertThat(completedDO.getVersion()).isEqualTo(1L);

        TimerExecutionDO exec = timerExecutionMapper.selectOne(TimerExecutionDO::getTaskId, validId);
        assertThat(exec).as("成功转换写入execution审计").isNotNull();
        assertThat(exec.getStatus()).as("成功执行审计状态为20").isEqualTo((short) 20);
        assertThat(exec.getErrorCode()).isNull();
        assertThat(exec.getErrorDigest()).isNull();
        assertThat(exec.getResultDigest()).isEqualTo(resultDigest);

        // 2. 租约已过期：lease_until <= now -> 失格，0行更新，严禁写入execution审计
        Long expiredId = task(TimerTaskMapper.STATUS_EXECUTING, 1, 3,
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        TimerClaimService.ClaimedTask expiredTask = new TimerClaimService.ClaimedTask(expiredId, "TMR_CP_EX", "B0_FIXTURE", 1, 1L, "{}");
        timer.complete(expiredTask, "node-a", startedAt, "SUCCESS", resultDigest);

        assertThat(timerMapper.selectById(expiredId).getStatus()).isEqualTo(TimerTaskMapper.STATUS_EXECUTING);
        int expiredExecCount = jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_execution WHERE task_id=?", Integer.class, expiredId);
        assertThat(expiredExecCount).as("租约过期失格不得记录execution审计").isEqualTo(0);

        // 3. FencingToken 不匹配 -> 失格，0行更新，严禁写入execution审计
        Long wrongTokenId = task(TimerTaskMapper.STATUS_EXECUTING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        TimerClaimService.ClaimedTask wrongTokenTask = new TimerClaimService.ClaimedTask(wrongTokenId, "TMR_CP_WT", "B0_FIXTURE", 1, 99L, "{}");
        timer.complete(wrongTokenTask, "node-a", startedAt, "SUCCESS", resultDigest);

        assertThat(timerMapper.selectById(wrongTokenId).getStatus()).isEqualTo(TimerTaskMapper.STATUS_EXECUTING);
        int wrongTokenExecCount = jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_execution WHERE task_id=?", Integer.class, wrongTokenId);
        assertThat(wrongTokenExecCount).as("Token失格不得记录execution审计").isEqualTo(0);
    }

    // ==========================================
    // Cycle 4: Timer fail / finishAttempt audit alignment and disqualification protection
    // ==========================================

    @Test
    @Order(9)
    void timerFailAlignsExecutionAuditForRetryAndDeadLetter() {
        LocalDateTime startedAt = databaseClock.now().minusSeconds(3);
        byte[] retryErrorDigest = new byte[32];
        retryErrorDigest[0] = 7;
        retryErrorDigest[31] = 8;

        // 1. 普通失败：deadLetter=false -> 任务迁RETRY(30)，清空租约，更新nextFireAt；execution状态为STATUS_EXEC_RETRY(30)，errorDigest填入，resultDigest为空
        Long retryId = task(TimerTaskMapper.STATUS_EXECUTING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        TimerClaimService.ClaimedTask retryTask = new TimerClaimService.ClaimedTask(retryId, "TMR_F_R", "B0_FIXTURE", 1, 1L, "{}");
        LocalDateTime beforeFire = timerMapper.selectById(retryId).getNextFireAt();

        timer.fail(retryTask, "node-a", startedAt, "BIZ_ERR", retryErrorDigest, Duration.ofSeconds(15), false);

        TimerTaskDO retryDO = timerMapper.selectById(retryId);
        assertThat(retryDO.getStatus()).as("普通失败转为RETRY").isEqualTo(TimerTaskMapper.STATUS_RETRY);
        assertThat(retryDO.getLeaseOwner()).isNull();
        assertThat(retryDO.getLeaseUntil()).isNull();
        assertThat(retryDO.getNextFireAt()).isAfter(beforeFire);
        assertThat(retryDO.getLastErrorCode()).isEqualTo("BIZ_ERR");
        assertThat(retryDO.getVersion()).isEqualTo(1L);

        TimerExecutionDO retryExec = timerExecutionMapper.selectOne(TimerExecutionDO::getTaskId, retryId);
        assertThat(retryExec).as("普通失败写入执行审计").isNotNull();
        assertThat(retryExec.getStatus()).as("审计状态为STATUS_EXEC_RETRY(30)").isEqualTo((short) 30);
        assertThat(retryExec.getErrorCode()).isEqualTo("BIZ_ERR");
        assertThat(retryExec.getErrorDigest()).as("失败摘要正确写入errorDigest").isEqualTo(retryErrorDigest);
        assertThat(retryExec.getResultDigest()).as("失败审计resultDigest为空").isNull();

        // 2. 死信失败：deadLetter=true -> 任务迁DEAD(60)，清空租约，保留原nextFireAt；execution状态为STATUS_EXEC_DEAD(40)，errorDigest填入，resultDigest为空
        byte[] deadErrorDigest = new byte[32];
        deadErrorDigest[0] = 9;
        deadErrorDigest[31] = 9;

        Long deadId = task(TimerTaskMapper.STATUS_EXECUTING, 3, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        TimerClaimService.ClaimedTask deadTask = new TimerClaimService.ClaimedTask(deadId, "TMR_F_D", "B0_FIXTURE", 3, 1L, "{}");
        LocalDateTime originalFireAt = timerMapper.selectById(deadId).getNextFireAt();

        timer.fail(deadTask, "node-a", startedAt, "FATAL_ERR", deadErrorDigest, Duration.ofSeconds(15), true);

        TimerTaskDO deadDO = timerMapper.selectById(deadId);
        assertThat(deadDO.getStatus()).as("死信失败转为DEAD").isEqualTo(TimerTaskMapper.STATUS_DEAD);
        assertThat(deadDO.getLeaseOwner()).isNull();
        assertThat(deadDO.getLeaseUntil()).isNull();
        assertThat(deadDO.getNextFireAt()).as("死信保留非空原计划时间").isNotNull().isEqualTo(originalFireAt);
        assertThat(deadDO.getLastErrorCode()).isEqualTo("FATAL_ERR");
        assertThat(deadDO.getVersion()).isEqualTo(1L);

        TimerExecutionDO deadExec = timerExecutionMapper.selectOne(TimerExecutionDO::getTaskId, deadId);
        assertThat(deadExec).as("死信失败写入执行审计").isNotNull();
        assertThat(deadExec.getStatus()).as("审计状态为STATUS_EXEC_DEAD(40)").isEqualTo((short) 40);
        assertThat(deadExec.getErrorCode()).isEqualTo("FATAL_ERR");
        assertThat(deadExec.getErrorDigest()).as("失败摘要正确写入errorDigest").isEqualTo(deadErrorDigest);
        assertThat(deadExec.getResultDigest()).as("失败审计resultDigest为空").isNull();
    }

    @Test
    @Order(10)
    void timerFailDisqualifiedNeverRecordsExecution() {
        LocalDateTime startedAt = databaseClock.now().minusSeconds(3);
        byte[] errorDigest = new byte[32];
        errorDigest[0] = 5;

        // 1. 租约已过期：lease_until <= now -> 失格，0行更新，严禁写入execution审计
        Long expiredId = task(TimerTaskMapper.STATUS_EXECUTING, 1, 3,
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        TimerClaimService.ClaimedTask expiredTask = new TimerClaimService.ClaimedTask(expiredId, "TMR_F_EX", "B0_FIXTURE", 1, 1L, "{}");

        timer.fail(expiredTask, "node-a", startedAt, "TIMEOUT", errorDigest, Duration.ofSeconds(10), false);

        assertThat(timerMapper.selectById(expiredId).getStatus()).isEqualTo(TimerTaskMapper.STATUS_EXECUTING);
        int expiredExecCount = jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_execution WHERE task_id=?", Integer.class, expiredId);
        assertThat(expiredExecCount).as("租约过期失格不得记录execution审计").isEqualTo(0);

        // 2. FencingToken 不匹配 -> 失格，0行更新，严禁写入execution审计
        Long wrongTokenId = task(TimerTaskMapper.STATUS_EXECUTING, 1, 3,
                "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 SECOND)",
                "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)", "node-a", 1L);
        TimerClaimService.ClaimedTask wrongTokenTask = new TimerClaimService.ClaimedTask(wrongTokenId, "TMR_F_WT", "B0_FIXTURE", 1, 99L, "{}");

        timer.fail(wrongTokenTask, "node-a", startedAt, "TIMEOUT", errorDigest, Duration.ofSeconds(10), false);

        assertThat(timerMapper.selectById(wrongTokenId).getStatus()).isEqualTo(TimerTaskMapper.STATUS_EXECUTING);
        int wrongTokenExecCount = jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_execution WHERE task_id=?", Integer.class, wrongTokenId);
        assertThat(wrongTokenExecCount).as("Token失格不得记录execution审计").isEqualTo(0);
    }

    // ==========================================
    // Fixture Helpers
    // ==========================================

    private static Long event(short state, int attempts, int max, String leaseSql, String planSql,
                              String owner, long fencingToken) {
        String key = UUID.randomUUID().toString().replace("-", "");
        String ownerSql = owner == null ? "NULL" : "'" + owner + "'";
        jdbc.update("INSERT INTO sc_outbox_event(event_id,aggregate_type,aggregate_id,aggregate_version,"
                + "event_type,target_type,target_name,payload_version,payload_json,available_at,status,"
                + "attempt_count,max_attempts,lease_owner,lease_until,fencing_token,trace_id,last_error_code,"
                + "created_at,updated_at,version) VALUES(?,'B0_FIXTURE',?,1,'B0_FIXTURE','LOCAL','b0-test',"
                + "1,'{}'," + planSql + ",?,?,?," + ownerSql + "," + leaseSql
                + ",?,'b0-trace',NULL,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),0)",
                key, key, state, attempts, max, fencingToken);
        return jdbc.queryForObject("SELECT id FROM sc_outbox_event WHERE event_id=?", Long.class, key);
    }

    private static Long task(short state, int attempts, int max, String leaseSql, String planSql,
                             String owner, long fencingToken) {
        String key = UUID.randomUUID().toString().replace("-", "");
        String ownerSql = owner == null ? "NULL" : "'" + owner + "'";
        jdbc.update("INSERT INTO sc_timer_task(task_no,task_type,idempotency_key,payload_version,payload_json,"
                + "schedule_type,next_fire_at,status,attempt_count,max_attempts,lease_owner,lease_until,"
                + "fencing_token,last_error_code,created_at,updated_at,version) VALUES(?,'B0_FIXTURE',?,1,'{}',"
                + "'ONCE'," + planSql + ",?,?,?," + ownerSql + "," + leaseSql
                + ",?,NULL,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),0)",
                key, key, state, attempts, max, fencingToken);
        return jdbc.queryForObject("SELECT id FROM sc_timer_task WHERE task_no=?", Long.class, key);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxDeliveryClaimService.class, TimerClaimService.class})
    static class Fixture { }
}
