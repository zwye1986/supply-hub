package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCallbackProperties;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCallResult;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCommunicationFacade;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentIdempotencyKeys;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.BOrderSettlementStatus;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSetResolver;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.DatabasePersistentTimer;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 MySQL 8.4 上的华昇停启与宕机恢复集成测试。
 *
 * <p>测试覆盖：
 * 1. 认领后未发宕机：关闭 Spring 上下文并重启，租约到期后看门狗驱动新代次 QUERY 原单，旧 Task/permit 迟到跳过；
 * 2. 响应后结果落库前宕机：关闭 Spring 上下文并重启，看门狗以原单 QUERY 恢复状态，绝不重下 PLACE；
 * 3. 下一步已创建前后宕机：关闭 Spring 上下文并重启，下一步 Operation 由持久 EXECUTE Timer 驱动完成；
 * 4. QUERY 认领后宕机：关闭 Spring 上下文并重启，新代次租约看门狗接续驱动；
 * 5. 准备看门狗收尾：5分钟未认领且无发送 marker 的死信步骤转 MANUAL 并保留资金；
 * 6. 持锁调用超租约：apply 失格只观察，watchdog 锁忙抛出 HuashengLockBusyException，释放锁后重试驱动 QUERY。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#5-资格、唯一下一步与宕机恢复}。</p>
 */
class HuashengRestartIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("huasheng_restart")
            .withUsername("supply_chain")
            .withPassword("supply_chain");
    private static final String EXTERNAL_URL = System.getProperty("supply-chain.test.jdbc-url");

    private static final Long CONNECTOR_VER_ID = 200L;
    private static final Long OFFER_VER_ID = 100L;
    private static final Long CREDENTIAL_SET_ID = 400L;

    private ConfigurableApplicationContext currentContext;

    @BeforeAll
    static void startAll() {
        if (EXTERNAL_URL == null) {
            MYSQL.start();
        }
    }

    @AfterAll
    static void stopAll() {
        if (EXTERNAL_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @BeforeEach
    void setUp() {
        OutboundCallRecorder.reset();
        currentContext = createContext();
    }

    @AfterEach
    void tearDown() {
        closeContext();
    }

    private void closeContext() {
        if (currentContext != null) {
            currentContext.close();
            currentContext = null;
        }
    }

    private void restartContext() {
        closeContext();
        currentContext = createContext();
    }

    private ConfigurableApplicationContext createContext() {
        String keyBase64 = Base64.getEncoder().encodeToString(new byte[32]);
        return new SpringApplicationBuilder(TestConfig.class)
                .web(WebApplicationType.NONE)
                .properties(Map.ofEntries(
                        Map.entry("aifuli.supply-chain.enabled", true),
                        Map.entry("aifuli.supply-chain.datasource.url", EXTERNAL_URL == null ? MYSQL.getJdbcUrl() : EXTERNAL_URL),
                        Map.entry("aifuli.supply-chain.datasource.username", EXTERNAL_URL == null ? MYSQL.getUsername()
                                : System.getProperty("supply-chain.test.jdbc-username", "root")),
                        Map.entry("aifuli.supply-chain.datasource.password", EXTERNAL_URL == null ? MYSQL.getPassword()
                                : System.getProperty("supply-chain.test.jdbc-password", "")),
                        Map.entry("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"),
                        Map.entry("aifuli.supply-chain.security.key-version", "test-v1"),
                        Map.entry("aifuli.supply-chain.security.envelope-key-base64", keyBase64),
                        Map.entry("aifuli.supply-chain.security.fingerprint-key-base64", keyBase64),
                        Map.entry("aifuli.supply-chain.timer.enabled", false)
                )).run();
    }

    @Test
    void restartedTimerEngineRetriesBusyLockThenQueriesOriginalOrder() {
        var fixture = seedOrderAggregate("HS_RST_ENGINE", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();
        String billno = fixture.bOrder().getBillno();
        currentContext.getBean(HuashengFulfillmentLifecycle.class).claimForExecution(opNo);
        restartContext();
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(currentContext.getBean("supplyChainDataSource", javax.sql.DataSource.class));
        // 独立测试库中其它场景的任务不参与本次调度，当前持久 watchdog 则改为已到期。
        jdbc.update("UPDATE sc_timer_task SET next_fire_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) WHERE status IN (0,30)");
        jdbc.update("UPDATE sc_fulfillment_operation SET execution_lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE operation_no=?", opNo);
        String key = "HS:LEASE:" + opNo + ":1";
        jdbc.update("UPDATE sc_timer_task SET next_fire_at=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE idempotency_key=?", key);
        var response = mockOrderResponse("HS_ENGINE_REMOTE", billno, HuashengOrderStatus.WAIT, 1000L);
        OutboundCallRecorder.setQueryAnswer(req -> new HuashengCallResult.Parsed<>(response, List.of(), true));
        var engine = currentContext.getBean(com.aifuli.module.supplychain.infrastructure.timer.TimerEngineLifecycle.class);
        var properties = currentContext.getBean(com.aifuli.module.supplychain.infrastructure.timer.TimerEngineProperties.class);
        properties.setEnabled(true); properties.setScanInterval(java.time.Duration.ofMillis(50));
        properties.setBackoffBase(java.time.Duration.ofMillis(100));
        var lock = currentContext.getBean(ReentrantLock.class);
        lock.lock();
        try {
            engine.start();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_execution e JOIN sc_timer_task t ON t.id=e.task_id WHERE t.idempotency_key=? AND e.error_code=?",
                            Long.class, key, HuashengLockBusyException.class.getSimpleName())).isGreaterThan(0));
            assertThat(OutboundCallRecorder.getQueryCount(billno)).isZero();
        } finally { lock.unlock(); }
        try {
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_execution e JOIN sc_timer_task t ON t.id=e.task_id WHERE t.idempotency_key=? AND e.status=20",
                            Long.class, key)).isEqualTo(1));
            assertThat(OutboundCallRecorder.getQueryCount(billno)).isEqualTo(1);
            assertThat(OutboundCallRecorder.getPlaceCount(billno)).isZero();
            assertThat(jdbc.queryForObject("SELECT execution_generation FROM sc_fulfillment_operation WHERE operation_no=?", Long.class, opNo)).isEqualTo(2);
        } finally { engine.stop(); }
    }

    @Test
    @DisplayName("1. 认领后未发宕机：重启后租约看门狗驱动新代次 QUERY 原单，旧 Task 迟到被跳过，外部 PLACE 保持 0 次")
    void crashAfterClaimBeforeHttpSend_watchdogDrivesNewGenerationQueryAndObsoleteTaskSkipped() {
        var fixture = seedOrderAggregate("HS_RST_CLAIM", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();
        String billno = fixture.bOrder().getBillno();

        // 步骤 1: 在 Context 1 中认领执行，生成代次 1、租约与发送 marker
        HuashengFulfillmentLifecycle lifecycle1 = currentContext.getBean(HuashengFulfillmentLifecycle.class);
        HuashengExecutionPermit permit1 = lifecycle1.claimForExecution(opNo);
        assertThat(permit1).isNotNull();
        assertThat(permit1.executionGeneration()).isEqualTo(1L);

        // 模拟进程在 HTTP 发送前宕机：关闭 Context 1
        restartContext();

        // 步骤 2: 在 Context 2 中（全新上下文、空 token 缓存），模拟租约已过
        FulfillmentOperationMapper opMapper2 = currentContext.getBean(FulfillmentOperationMapper.class);
        SupplyChainDatabaseClock clock2 = currentContext.getBean(SupplyChainDatabaseClock.class);
        HuashengLeaseWatchdogHandler leaseWatchdogHandler2 = currentContext.getBean(HuashengLeaseWatchdogHandler.class);
        HuashengFulfillmentRunner runner2 = currentContext.getBean(HuashengFulfillmentRunner.class);
        BOrderMapper bOrderMapper2 = currentContext.getBean(BOrderMapper.class);
        ObjectMapper objectMapper2 = currentContext.getBean(ObjectMapper.class);

        LocalDateTime now2 = clock2.now();
        // 模拟时间流逝导致租约到期
        opMapper2.update(null, new LambdaUpdateWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getOperationNo, opNo)
                .set(FulfillmentOperationDO::getExecutionLeaseUntil, now2.minusSeconds(10)));

        // 配置挡板：QUERY 原单返回 WAIT
        HuashengOrderResponse queryWaitResp = mockOrderResponse("HS_ORD_RST_01", billno, HuashengOrderStatus.WAIT, 1000L);
        OutboundCallRecorder.setQueryAnswer(req -> new HuashengCallResult.Parsed<>(queryWaitResp, List.of(), true));

        // 租约看门狗触发代次 1 恢复
        ObjectNode watchdogPayload = objectMapper2.createObjectNode();
        watchdogPayload.put("operationNo", opNo);
        watchdogPayload.put("expectedGeneration", 1L);
        String watchdogResult = leaseWatchdogHandler2.handle(
                new TimerTaskContext("T_LEASE_1", 1L, 1, 1L, "worker-1"), watchdogPayload);
        assertThat(watchdogResult).isEqualTo("NEXT_CONFIRM");

        // 断言外部调用：PLACE 确实从未发出（0 次），QUERY 发送 1 次且是原 billno
        assertThat(OutboundCallRecorder.getPlaceCount(billno)).isEqualTo(0);
        assertThat(OutboundCallRecorder.getQueryCount(billno)).isEqualTo(1);

        // 步骤 3: 迟到的旧代次任务尝试执行，断言因代次过时直接跳过
        String obsoleteExecuteResult = runner2.runExecute(opNo, "trace-old");
        assertThat(obsoleteExecuteResult).isEqualTo("ALREADY_COMPLETED"); // sequence 1 已被 advance 标记为 SUCCEEDED

        String obsoleteRecoveryResult = runner2.runRecovery(opNo, 1L, "trace-old");
        assertThat(obsoleteRecoveryResult).isEqualTo("ALREADY_COMPLETED");

        // 验证已生成 sequence 2 CONFIRM_ORDER
        List<FulfillmentOperationDO> ops = opMapper2.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        assertThat(ops).hasSize(2);
        FulfillmentOperationDO seq2 = ops.get(1);
        assertThat(seq2.getSequenceNo()).isEqualTo(2);
        assertThat(seq2.getAdapterStepCode()).isEqualTo(AdapterStepCode.CONFIRM_ORDER.name());
        assertThat(seq2.getStatus()).isEqualTo(SupplierOrderOperationStatus.PENDING.getCode());
    }

    @Test
    @DisplayName("2. 响应后结果落库前宕机：重启后看门狗 QUERY 原单收敛原单号，绝不重复 PLACE")
    void crashAfterHttpResponseBeforeApplyCommitted_watchdogDrivesNewGenerationQuery() {
        var fixture = seedOrderAggregate("HS_RST_RESP", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();
        String billno = fixture.bOrder().getBillno();

        // 步骤 1: Context 1 中认领并外呼 PLACE，供应商已产生单号 HS_ORD_RST_02
        HuashengFulfillmentLifecycle lifecycle1 = currentContext.getBean(HuashengFulfillmentLifecycle.class);
        HuashengExecutionPermit permit1 = lifecycle1.claimForExecution(opNo);
        assertThat(permit1).isNotNull();

        // 记录外部确实收到了 PLACE
        OutboundCallRecorder.recordPlace(billno);

        // 模拟在结果 apply 落库前宕机：关闭 Context 1
        restartContext();

        // 步骤 2: Context 2 重启，租约到期后看门狗触发 QUERY 原单
        FulfillmentOperationMapper opMapper2 = currentContext.getBean(FulfillmentOperationMapper.class);
        SupplyChainDatabaseClock clock2 = currentContext.getBean(SupplyChainDatabaseClock.class);
        HuashengLeaseWatchdogHandler leaseWatchdogHandler2 = currentContext.getBean(HuashengLeaseWatchdogHandler.class);
        ObjectMapper objectMapper2 = currentContext.getBean(ObjectMapper.class);

        LocalDateTime now2 = clock2.now();
        opMapper2.update(null, new LambdaUpdateWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getOperationNo, opNo)
                .set(FulfillmentOperationDO::getExecutionLeaseUntil, now2.minusSeconds(10)));

        // 配置 QUERY 返回远端已落单的真实单号 HS_ORD_RST_02
        HuashengOrderResponse queryResp = mockOrderResponse("HS_ORD_RST_02", billno, HuashengOrderStatus.WAIT, 1000L);
        OutboundCallRecorder.setQueryAnswer(req -> new HuashengCallResult.Parsed<>(queryResp, List.of(), true));

        ObjectNode watchdogPayload = objectMapper2.createObjectNode();
        watchdogPayload.put("operationNo", opNo);
        watchdogPayload.put("expectedGeneration", 1L);
        String result = leaseWatchdogHandler2.handle(
                new TimerTaskContext("T_LEASE_2", 2L, 1, 1L, "worker-1"), watchdogPayload);
        assertThat(result).isEqualTo("NEXT_CONFIRM");

        // 外部计数验证：PLACE 恰好 1 次，QUERY 恰好 1 次，绝无第二次 PLACE
        assertThat(OutboundCallRecorder.getPlaceCount(billno)).isEqualTo(1);
        assertThat(OutboundCallRecorder.getQueryCount(billno)).isEqualTo(1);

        // sequence 2 已创建且持有原单号 HS_ORD_RST_02
        List<FulfillmentOperationDO> ops = opMapper2.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        assertThat(ops).hasSize(2);
        assertThat(ops.get(1).getSequenceNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("3. 下一步已创建前后宕机：重启后新上下文直接由 EXECUTE Timer 驱动后续步骤")
    void crashAfterNextStepCreatedBeforeClaim_nextStepExecutedAfterRestart() {
        var fixture = seedOrderAggregate("HS_RST_NEXT", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();
        String billno = fixture.bOrder().getBillno();

        // 步骤 1: Context 1 执行 PLACE 成功，并原子提交 sequence 1 SUCCEEDED 与 sequence 2 PENDING
        HuashengOrderResponse placeResp = mockOrderResponse("HS_ORD_RST_03", billno, HuashengOrderStatus.WAIT, 1000L);
        OutboundCallRecorder.setPlaceAnswer(req -> new HuashengCallResult.Parsed<>(placeResp, List.of(), true));

        HuashengFulfillmentRunner runner1 = currentContext.getBean(HuashengFulfillmentRunner.class);
        String r1 = runner1.runExecute(opNo, "trace-p1");
        assertThat(r1).isEqualTo("NEXT_CONFIRM");

        // 宕机重启
        restartContext();

        // 步骤 2: Context 2 中执行 sequence 2
        FulfillmentOperationMapper opMapper2 = currentContext.getBean(FulfillmentOperationMapper.class);
        HuashengExecuteTimerHandler executeHandler2 = currentContext.getBean(HuashengExecuteTimerHandler.class);
        ObjectMapper objectMapper2 = currentContext.getBean(ObjectMapper.class);

        FulfillmentOperationDO seq2 = opMapper2.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .eq(FulfillmentOperationDO::getSequenceNo, 2));
        assertThat(seq2).isNotNull();
        assertThat(seq2.getStatus()).isEqualTo(SupplierOrderOperationStatus.PENDING.getCode());

        // 配置 CONFIRM 返回 SUCCESS 且 isTake=Y
        HuashengOrderResponse confirmResp = mockOrderResponseWithTake(
                "HS_ORD_RST_03", billno, HuashengOrderStatus.SUCCESS, HuashengTakeStatus.Y, 1000L);
        OutboundCallRecorder.setConfirmAnswer(req -> new HuashengCallResult.Parsed<>(confirmResp, List.of(), true));

        ObjectNode execPayload = objectMapper2.createObjectNode();
        execPayload.put("operationNo", seq2.getOperationNo());
        String r2 = executeHandler2.handle(new TimerTaskContext("T_EXEC_2", 3L, 1, 1L, "worker-1"), execPayload);
        assertThat(r2).isEqualTo("NEXT_TAKE");

        // sequence 3 TAKE_CARD 存在
        FulfillmentOperationDO seq3 = opMapper2.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .eq(FulfillmentOperationDO::getSequenceNo, 3));
        assertThat(seq3).isNotNull();
        assertThat(seq3.getAdapterStepCode()).isEqualTo(AdapterStepCode.TAKE_CARD.name());

        assertThat(OutboundCallRecorder.getPlaceCount(billno)).isEqualTo(1);
        assertThat(OutboundCallRecorder.getConfirmCount("HS_ORD_RST_03")).isEqualTo(1);
    }

    @Test
    @DisplayName("4. QUERY 认领后宕机：重启后由新代次看门狗接续驱动，正确推进后续步骤")
    void crashAfterQueryClaimBeforeApply_nextGenerationWatchdogDrivesQuery() {
        var fixture = seedOrderAggregate("HS_RST_Q_CRASH", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();
        String billno = fixture.bOrder().getBillno();

        // 步骤 1: PLACE 超时变为 RESULT_UNKNOWN (代次 1)
        HuashengFailureDiagnostics diag = mock(HuashengFailureDiagnostics.class);
        when(diag.reasonCode()).thenReturn(HuashengFailureDiagnostics.HuashengFailureReasonCode.TRANSPORT_FAILURE);
        OutboundCallRecorder.setPlaceAnswer(req -> new HuashengCallResult.TransportFailure<>(diag, List.of(), true));

        HuashengFulfillmentRunner runner1 = currentContext.getBean(HuashengFulfillmentRunner.class);
        runner1.runExecute(opNo, "trace-p-timeout");

        // 步骤 2: Recovery 认领了代次 2 (generation 2, EXECUTING)
        HuashengFulfillmentLifecycle lifecycle1 = currentContext.getBean(HuashengFulfillmentLifecycle.class);
        HuashengExecutionPermit permit2 = lifecycle1.claimForRecovery(opNo);
        assertThat(permit2).isNotNull();
        assertThat(permit2.executionGeneration()).isEqualTo(2L);

        // 宕机重启
        restartContext();

        // 步骤 3: Context 2 中代次 2 租约到期，看门狗接续触发代次 3 QUERY
        FulfillmentOperationMapper opMapper2 = currentContext.getBean(FulfillmentOperationMapper.class);
        SupplyChainDatabaseClock clock2 = currentContext.getBean(SupplyChainDatabaseClock.class);
        HuashengLeaseWatchdogHandler leaseWatchdogHandler2 = currentContext.getBean(HuashengLeaseWatchdogHandler.class);
        ObjectMapper objectMapper2 = currentContext.getBean(ObjectMapper.class);

        LocalDateTime now2 = clock2.now();
        opMapper2.update(null, new LambdaUpdateWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getOperationNo, opNo)
                .set(FulfillmentOperationDO::getExecutionLeaseUntil, now2.minusSeconds(10)));

        HuashengOrderResponse queryResp = mockOrderResponseWithTake(
                "HS_ORD_RST_04", billno, HuashengOrderStatus.SUCCESS, HuashengTakeStatus.Y, 1000L);
        OutboundCallRecorder.setQueryAnswer(req -> new HuashengCallResult.Parsed<>(queryResp, List.of(), true));

        ObjectNode watchdogPayload = objectMapper2.createObjectNode();
        watchdogPayload.put("operationNo", opNo);
        watchdogPayload.put("expectedGeneration", 2L);
        String result = leaseWatchdogHandler2.handle(
                new TimerTaskContext("T_LEASE_3", 4L, 1, 1L, "worker-1"), watchdogPayload);
        assertThat(result).isEqualTo("NEXT_TAKE");

        // 验证当前操作 generation 已推进到 3
        FulfillmentOperationDO opAfterRecovery = opMapper2.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        assertThat(opAfterRecovery.getExecutionGeneration()).isEqualTo(3L);
    }

    @Test
    @DisplayName("5. 准备看门狗收尾：未发送 marker 且仍处于 PENDING 的死信步骤转 MANUAL 并保留资金")
    void preparationWatchdogClosesDeadLetteredPendingStepToManual() {
        var fixture = seedOrderAggregate("HS_PREP_DOG", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();

        // 初始为 PENDING，且 businessSendPossibleAt 为空
        restartContext();

        HuashengPreparationWatchdogHandler prepHandler2 = currentContext.getBean(HuashengPreparationWatchdogHandler.class);
        FulfillmentOperationMapper opMapper2 = currentContext.getBean(FulfillmentOperationMapper.class);
        BOrderMapper bOrderMapper2 = currentContext.getBean(BOrderMapper.class);
        SupplierOrderLifecycle supplierOrderLifecycle2 = currentContext.getBean(SupplierOrderLifecycle.class);
        ObjectMapper objectMapper2 = currentContext.getBean(ObjectMapper.class);

        ObjectNode payload = objectMapper2.createObjectNode();
        payload.put("operationNo", opNo);

        String result = prepHandler2.handle(new TimerTaskContext("T_PREP_1", 5L, 1, 1L, "worker-1"), payload);
        assertThat(result).isEqualTo("TRANSITIONED_TO_MANUAL");

        FulfillmentOperationDO op = opMapper2.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        assertThat(op.getStatus()).isEqualTo(SupplierOrderOperationStatus.MANUAL.getCode());

        // 资金绝不释放
        Mockito.verifyNoInteractions(supplierOrderLifecycle2);
        BOrderDO bOrder = bOrderMapper2.selectById(fixture.bOrder().getId());
        assertThat(bOrder.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.MANUAL.getCode());
    }

    @Test
    @DisplayName("6. 持锁调用超租约：apply 失格只观察，watchdog 锁忙抛出 HuashengLockBusyException，释放锁后重试驱动 QUERY")
    void lockHeldPastLease_lateApplyDisqualifiedAsObservationOnly_watchdogRetryDrivesQuery() throws Exception {
        var fixture = seedOrderAggregate("HS_SLOW_CALL", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();
        String billno = fixture.bOrder().getBillno();

        HuashengFulfillmentRunner runner = currentContext.getBean(HuashengFulfillmentRunner.class);
        HuashengFulfillmentLifecycle lifecycle = currentContext.getBean(HuashengFulfillmentLifecycle.class);
        FulfillmentOperationMapper opMapper = currentContext.getBean(FulfillmentOperationMapper.class);
        SupplyChainDatabaseClock clock = currentContext.getBean(SupplyChainDatabaseClock.class);
        HuashengLeaseWatchdogHandler leaseWatchdogHandler = currentContext.getBean(HuashengLeaseWatchdogHandler.class);
        ObjectMapper objectMapper = currentContext.getBean(ObjectMapper.class);

        // 步骤 1: 认领执行 (generation 1, leaseUntil=now+150s)
        HuashengExecutionPermit permit1 = lifecycle.claimForExecution(opNo);
        assertThat(permit1).isNotNull();

        // 步骤 2: 模拟一个慢调用持有互斥锁
        ReentrantLock lock = runner.executionLock();
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);

        Thread lockHoldingThread = new Thread(() -> {
            lock.lock();
            try {
                lockAcquiredLatch.countDown();
                releaseLockLatch.await();
            } catch (InterruptedException ignored) {
            } finally {
                lock.unlock();
            }
        });
        lockHoldingThread.start();
        lockAcquiredLatch.await();

        try {
            // 步骤 3: 锁忙期间，Watchdog 触发必须抛出 HuashengLockBusyException 而不得返回成功或吞掉
            ObjectNode watchdogPayload = objectMapper.createObjectNode();
            watchdogPayload.put("operationNo", opNo);
            watchdogPayload.put("expectedGeneration", 1L);

            assertThatThrownBy(() -> leaseWatchdogHandler.handle(
                    new TimerTaskContext("T_BUSY", 6L, 1, 1L, "worker-1"), watchdogPayload))
                    .isInstanceOf(HuashengLockBusyException.class);

            // 步骤 4: 模拟调用耗时超过租约，修改数据库 leaseUntil 为过去时间
            LocalDateTime now = clock.now();
            opMapper.update(null, new LambdaUpdateWrapper<FulfillmentOperationDO>()
                    .eq(FulfillmentOperationDO::getOperationNo, opNo)
                    .set(FulfillmentOperationDO::getExecutionLeaseUntil, now.minusSeconds(10)));

            // 迟到调用的 apply 尝试：因租约已过被判定为失格 (disqualified)，只作安全观察，不推进状态机
            HuashengStepFact fact = new HuashengStepFact(
                    HuashengStepFact.Endpoint.PLACE,
                    HuashengStepFact.StepResult.ACCEPTED,
                    billno, "HS_ORD_SLOW", 1, "10.00", 1000L,
                    HuashengStepFact.Status.WAIT,
                    HuashengStepFact.RefundStatus.ABSENT,
                    HuashengStepFact.IsTake.ABSENT,
                    null, List.of()
            );
            OperationOutcome outcome = OperationOutcome.builder()
                    .outcomeClass(OperationOutcomeClass.ACCEPTED)
                    .externalOrderNo("HS_ORD_SLOW")
                    .reportedSettlementAmount(1000L)
                    .protocolFact(fact)
                    .build();

            HuashengApplyResult lateApplyResult = lifecycle.apply(permit1, outcome, "trace-slow");
            assertThat(lateApplyResult.applied()).isFalse();
            assertThat(lateApplyResult.message()).contains("Execution lease expired");

            // 状态机依然保持在 EXECUTING，未创建 sequence 2
            FulfillmentOperationDO opAfterLateApply = opMapper.selectOne(
                    new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
            assertThat(opAfterLateApply.getStatus()).isEqualTo(SupplierOrderOperationStatus.EXECUTING.getCode());
        } finally {
            // 释放锁
            releaseLockLatch.countDown();
            lockHoldingThread.join();
        }

        // 步骤 5: 释放锁后，Watchdog 重试触发，成功驱动代次 2 QUERY 并正常推进
        HuashengOrderResponse queryResp = mockOrderResponse("HS_ORD_SLOW", billno, HuashengOrderStatus.WAIT, 1000L);
        OutboundCallRecorder.setQueryAnswer(req -> new HuashengCallResult.Parsed<>(queryResp, List.of(), true));

        ObjectNode watchdogPayload = objectMapper.createObjectNode();
        watchdogPayload.put("operationNo", opNo);
        watchdogPayload.put("expectedGeneration", 1L);

        String successResult = leaseWatchdogHandler.handle(
                new TimerTaskContext("T_RETRY", 7L, 1, 1L, "worker-1"), watchdogPayload);
        assertThat(successResult).isEqualTo("NEXT_CONFIRM");

        // 验证已创建 sequence 2 CONFIRM_ORDER
        List<FulfillmentOperationDO> ops = opMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        assertThat(ops).hasSize(2);
        assertThat(ops.get(1).getSequenceNo()).isEqualTo(2);
    }

    private static final AtomicLong RECEIPT_SEQ = new AtomicLong(70000L);

    private TestAggregateFixture seedOrderAggregate(String prefix, AdapterStepCode step, int sequenceNo, String orderSn) {
        SupplyChainDatabaseClock databaseClock = currentContext.getBean(SupplyChainDatabaseClock.class);
        BusinessNoGenerator businessNoGenerator = currentContext.getBean(BusinessNoGenerator.class);
        HuashengCheckpointCodec checkpointCodec = currentContext.getBean(HuashengCheckpointCodec.class);
        SupplyChainOrderMapper orderMapper = currentContext.getBean(SupplyChainOrderMapper.class);
        FulfillmentRequestMapper requestMapper = currentContext.getBean(FulfillmentRequestMapper.class);
        SupplierMapper supplierMapper = currentContext.getBean(SupplierMapper.class);
        SupplierOfferVersionMapper offerVersionMapper = currentContext.getBean(SupplierOfferVersionMapper.class);
        BOrderMapper bOrderMapper = currentContext.getBean(BOrderMapper.class);
        FulfillmentOperationMapper operationMapper = currentContext.getBean(FulfillmentOperationMapper.class);

        LocalDateTime now = databaseClock.now();
        String orderNo = businessNoGenerator.next(BusinessNoPrefixes.SC);
        String reqNo = businessNoGenerator.next(BusinessNoPrefixes.REQ);
        String bOrderNo = businessNoGenerator.next(BusinessNoPrefixes.BO);
        String billno = businessNoGenerator.next(BusinessNoPrefixes.BILL);
        String opNo = businessNoGenerator.next(BusinessNoPrefixes.OP);

        long receiptId = RECEIPT_SEQ.incrementAndGet();
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .orderNo(orderNo).orderType("CARD").buyerId(101L)
                .buyerCodeSnapshot("BUYER_101").buyerNameSnapshot("buyer")
                .buyerOrderNo("BUYER_ORD_" + prefix + "_" + receiptId)
                .acceptanceReceiptId(receiptId)
                .platformSkuId(1001L)
                .detailType(com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT)
                .currency("CNY")
                .projectId(201L).orderStatus(SupplyChainOrderStatus.PROCESSING.getCode())
                .aftersaleStatus((short) 0).businessStage("FULFILLING").detailVersion(1L)
                .quantity(1L).buyerSettlementAmount(1000L)
                .version(0L).createdAt(now).updatedAt(now).build();
        orderMapper.insert(order);

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .requestNo(reqNo).orderId(order.getId()).buyerId(101L)
                .listingId(10L).listingVersionId(100L).platformSkuId(1001L)
                .targetSchemaCode("MOBILE_RECHARGE").targetSchemaVersion(1)
                .targetCiphertext(new byte[]{1, 2, 3}).targetDigest(new byte[32])
                .targetKeyVersion("v1")
                .status(SupplierOrderOperationStatus.EXECUTING.getCode()).requestedQuantity(1L).fulfilledQuantity(0L)
                .aggregateVersion(0L).createdAt(now).updatedAt(now).build();
        requestMapper.insert(request);

        order.setDetailId(request.getId());
        orderMapper.updateById(order);

        SupplierDO supplier = SupplierDO.builder()
                .supplierCode("SPL_HS_" + prefix).supplierName("华昇供应商").shortName("华昇")
                .supplierType("STANDARD_SUPPLIER")
                .status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        supplierMapper.insert(supplier);

        SupplierOfferVersionDO offerVersion = SupplierOfferVersionDO.builder()
                .offerId(receiptId).versionNo(1).externalSkuCode("1001")
                .supplierSettlementUnitPrice(1000L).currency("CNY")
                .connectorVersionId(CONNECTOR_VER_ID).inventorySourceId(1L)
                .minQuantity(1L).maxQuantity(10L).quantityStep(1L)
                .primaryCapabilityCode("ISSUE_ASSET").completionMode("QUERY_OR_CALLBACK")
                .createIdempotencyMode("DEDUP_ONLY").unknownResultPolicy("QUERY_ORDER")
                .partialDeliveryMode("FORBIDDEN").resultMultiplicity("NONE")
                .fulfillmentConfigSchemaVersion(1).fulfillmentConfigJson("{}")
                .validFrom(now.minusDays(1)).validTo(now.plusDays(30))
                .publishReason("华昇测试").publishedBy(1L).publishedAt(now)
                .createdAt(now).build();
        offerVersionMapper.insert(offerVersion);

        BOrderDO bOrder = BOrderDO.builder()
                .bOrderNo(bOrderNo).fulfillmentRequestId(request.getId()).orderId(order.getId())
                .attemptNo(1).supplierId(supplier.getId()).offerId(receiptId).offerVersionId(offerVersion.getId()).connectorVersionId(CONNECTOR_VER_ID)
                .inventorySourceId(1L).billno(billno).externalOrderNo(orderSn).idempotencyKey(bOrderNo)
                .requestedQuantity(1L).fulfilledQuantity(0L)
                .supplierSettlementUnitPrice(1000L).expectedSupplierSettlementAmount(1000L)
                .currency("CNY").fulfillmentStatus(BOrderFulfillmentStatus.PROCESSING.getCode())
                .settlementStatus(BOrderSettlementStatus.PENDING.getCode()).activeFlag(true)
                .version(0L).createdAt(now).updatedAt(now).build();
        bOrderMapper.insert(bOrder);

        request.setActiveBOrderId(bOrder.getId());
        requestMapper.updateById(request);

        String idempotencyKey = FulfillmentIdempotencyKeys.operation(bOrderNo, sequenceNo);
        byte[] configDigest = sha256((idempotencyKey + "|100|200").getBytes(StandardCharsets.UTF_8));

        int confirmCount = 0;
        int takeCount = 0;
        HuashengCheckpoint.Binding binding = new HuashengCheckpoint.Binding(
                bOrder.getId(), bOrderNo, opNo, sequenceNo, step, CONNECTOR_VER_ID, offerVersion.getId(), configDigest);
        HuashengCheckpoint.PlaceParameters place = new HuashengCheckpoint.PlaceParameters(
                billno, 1001L, 1, 1800, "13800138000", "https://callback.aifuli.com/callbacks/suppliers/huasheng/200");
        HuashengCheckpoint.Progress progress = new HuashengCheckpoint.Progress(
                0, confirmCount, takeCount, now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusMinutes(30), "seed-trace");
        HuashengCheckpoint.Observation observation = null;
        if (orderSn != null) {
            observation = new HuashengCheckpoint.Observation(
                    com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint.PLACE,
                    orderSn,
                    billno,
                    1,
                    "10.00",
                    1000L,
                    HuashengOrderStatus.WAIT,
                    HuashengField.absent(),
                    HuashengField.absent()
            );
        }
        HuashengCheckpoint checkpoint = new HuashengCheckpoint(binding, place, orderSn, observation, progress, null);
        HuashengCheckpointCodec.Stored stored = checkpointCodec.encrypt(checkpoint);

        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .operationNo(opNo).bOrderId(bOrder.getId()).operationKind(FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(step.name()).sequenceNo(sequenceNo).idempotencyKey(idempotencyKey)
                .configDigest(configDigest).status(SupplierOrderOperationStatus.PENDING.getCode())
                .attemptCount(0).executionGeneration(0L).checkpointSchemaVersion(stored.schemaVersion())
                .checkpointKeyVersion(stored.keyVersion()).checkpointCiphertext(stored.ciphertext())
                .recoveryDeadlineAt(progress.recoveryDeadlineAt())
                .version(0L).createdAt(now).updatedAt(now).build();
        operationMapper.insert(operation);

        return new TestAggregateFixture(order, request, bOrder, operation);
    }

    private static HuashengOrderResponse mockOrderResponse(String orderSn, String outOrderSn, HuashengOrderStatus status, Long amountMinor) {
        return mockOrderResponseWithTake(orderSn, outOrderSn, status, null, amountMinor);
    }

    private static HuashengOrderResponse mockOrderResponseWithTake(
            String orderSn, String outOrderSn, HuashengOrderStatus status, HuashengTakeStatus isTake, Long amountMinor) {
        HuashengOrderResponse res = mock(HuashengOrderResponse.class);
        when(res.orderSn()).thenReturn(orderSn);
        when(res.outOrderSn()).thenReturn(outOrderSn);
        when(res.num()).thenReturn(1);
        when(res.status()).thenReturn(status);
        when(res.refundStatus()).thenReturn(Optional.empty());
        when(res.isTake()).thenReturn(Optional.ofNullable(isTake));

        HuashengPrice price = mock(HuashengPrice.class);
        when(price.rawYuan()).thenReturn("10.00");
        when(price.minorUnits()).thenReturn(amountMinor != null ? amountMinor : 1000L);
        when(res.totalPrice()).thenReturn(price);

        return res;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    record TestAggregateFixture(
            SupplyChainOrderDO order,
            FulfillmentRequestDO request,
            BOrderDO bOrder,
            FulfillmentOperationDO operation
    ) {}

    /**
     * 跨上下文持久记录的外部外呼挡板与去重账本。
     */
    static class OutboundCallRecorder {
        private static final Map<String, AtomicInteger> placeCalls = new ConcurrentHashMap<>();
        private static final Map<String, AtomicInteger> confirmCalls = new ConcurrentHashMap<>();
        private static final Map<String, AtomicInteger> queryCalls = new ConcurrentHashMap<>();
        private static final Map<String, AtomicInteger> takeCalls = new ConcurrentHashMap<>();

        private static volatile java.util.function.Function<HuashengPlaceRequest, HuashengCallResult<HuashengOrderResponse>> placeAnswer;
        private static volatile java.util.function.Function<HuashengConfirmRequest, HuashengCallResult<HuashengOrderResponse>> confirmAnswer;
        private static volatile java.util.function.Function<HuashengQueryRequest, HuashengCallResult<HuashengOrderResponse>> queryAnswer;
        private static volatile java.util.function.Function<HuashengTakeRequest, HuashengCallResult<HuashengTakeResponse>> takeAnswer;

        static void reset() {
            placeCalls.clear();
            confirmCalls.clear();
            queryCalls.clear();
            takeCalls.clear();
            placeAnswer = null;
            confirmAnswer = null;
            queryAnswer = null;
            takeAnswer = null;
        }

        static void recordPlace(String outOrderSn) {
            placeCalls.computeIfAbsent(outOrderSn, k -> new AtomicInteger()).incrementAndGet();
        }

        static void recordConfirm(String orderSn) {
            confirmCalls.computeIfAbsent(orderSn, k -> new AtomicInteger()).incrementAndGet();
        }

        static void recordQuery(String key) {
            queryCalls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }

        static void recordTake(String orderSn) {
            takeCalls.computeIfAbsent(orderSn, k -> new AtomicInteger()).incrementAndGet();
        }

        static int getPlaceCount(String outOrderSn) {
            return placeCalls.getOrDefault(outOrderSn, new AtomicInteger(0)).get();
        }

        static int getConfirmCount(String orderSn) {
            return confirmCalls.getOrDefault(orderSn, new AtomicInteger(0)).get();
        }

        static int getQueryCount(String key) {
            return queryCalls.getOrDefault(key, new AtomicInteger(0)).get();
        }

        static int getTakeCount(String orderSn) {
            return takeCalls.getOrDefault(orderSn, new AtomicInteger(0)).get();
        }

        static void setPlaceAnswer(java.util.function.Function<HuashengPlaceRequest, HuashengCallResult<HuashengOrderResponse>> answer) {
            placeAnswer = answer;
        }

        static void setConfirmAnswer(java.util.function.Function<HuashengConfirmRequest, HuashengCallResult<HuashengOrderResponse>> answer) {
            confirmAnswer = answer;
        }

        static void setQueryAnswer(java.util.function.Function<HuashengQueryRequest, HuashengCallResult<HuashengOrderResponse>> answer) {
            queryAnswer = answer;
        }

        static void setTakeAnswer(java.util.function.Function<HuashengTakeRequest, HuashengCallResult<HuashengTakeResponse>> answer) {
            takeAnswer = answer;
        }

        static HuashengCallResult<HuashengOrderResponse> nextPlaceResult(HuashengPlaceRequest req) {
            if (placeAnswer != null) return placeAnswer.apply(req);
            throw new IllegalStateException("Unexpected place call: " + req.outOrderSn());
        }

        static HuashengCallResult<HuashengOrderResponse> nextConfirmResult(HuashengConfirmRequest req) {
            if (confirmAnswer != null) return confirmAnswer.apply(req);
            throw new IllegalStateException("Unexpected confirm call: " + req.orderSn());
        }

        static HuashengCallResult<HuashengOrderResponse> nextQueryResult(HuashengQueryRequest req) {
            if (queryAnswer != null) return queryAnswer.apply(req);
            throw new IllegalStateException("Unexpected query call: " + req.outOrderSn());
        }

        static HuashengCallResult<HuashengTakeResponse> nextTakeResult(HuashengTakeRequest req) {
            if (takeAnswer != null) return takeAnswer.apply(req);
            throw new IllegalStateException("Unexpected take call: " + req.orderSn());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            SupplyChainDataSourceConfiguration.class,
            UlidGenerator.class,
            BusinessNoGenerator.class,
            AesGcmEnvelopeCrypto.class,
            HuashengCheckpointCodec.class,
            HuashengFulfillmentLifecycle.class,
            DatabasePersistentTimer.class,
            com.aifuli.module.supplychain.infrastructure.timer.TimerClaimService.class,
            com.aifuli.module.supplychain.infrastructure.timer.TimerEngineLifecycle.class,
            PersistentTimerHandlerRegistry.class
    })
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        ReentrantLock huashengExecutionLock() {
            return new ReentrantLock();
        }

        @Bean
        HuashengCommunicationFacade huashengCommunicationFacade() {
            HuashengCommunicationFacade facade = mock(HuashengCommunicationFacade.class);
            when(facade.place(any(), any(), any(), any())).thenAnswer(inv -> {
                HuashengPlaceRequest req = inv.getArgument(1);
                OutboundCallRecorder.recordPlace(req.outOrderSn());
                return OutboundCallRecorder.nextPlaceResult(req);
            });
            when(facade.confirm(any(), any(), any(), any())).thenAnswer(inv -> {
                HuashengConfirmRequest req = inv.getArgument(1);
                OutboundCallRecorder.recordConfirm(req.orderSn());
                return OutboundCallRecorder.nextConfirmResult(req);
            });
            when(facade.query(any(), any(), any(), any())).thenAnswer(inv -> {
                HuashengQueryRequest req = inv.getArgument(1);
                String key = req.outOrderSn().orElse(req.orderSn().orElse("unknown"));
                OutboundCallRecorder.recordQuery(key);
                return OutboundCallRecorder.nextQueryResult(req);
            });
            when(facade.take(any(), any(), any(), any())).thenAnswer(inv -> {
                HuashengTakeRequest req = inv.getArgument(1);
                OutboundCallRecorder.recordTake(req.orderSn());
                return OutboundCallRecorder.nextTakeResult(req);
            });
            return facade;
        }

        @Bean
        HuashengOperationExecutor huashengOperationExecutor(HuashengCommunicationFacade facade) {
            return new HuashengOperationExecutor(facade);
        }

        @Bean
        SupplierOrderLifecycle supplierOrderLifecycle() {
            return mock(SupplierOrderLifecycle.class);
        }

        @Bean
        PublishedConnectorConfigurationResolver connectorResolver() {
            PublishedConnectorConfigurationResolver resolver = mock(PublishedConnectorConfigurationResolver.class);
            PublishedConnectorConfiguration config = new PublishedConnectorConfiguration(
                    "CONN_HS_RST",
                    CONNECTOR_VER_ID,
                    1,
                    HuashengOrderV1Metadata.TYPE,
                    "https://open.huasheng.test",
                    "1234567890123456",
                    null,
                    "/callbacks/suppliers/huasheng",
                    java.util.Set.of(com.aifuli.module.supplychain.supplieradapter.metadata.Capability.ISSUE_ASSET),
                    CREDENTIAL_SET_ID,
                    com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.CREDENTIAL_SET,
                    3000,
                    5000,
                    Map.of("outMobile", "13800138000", "expireTime", "300"),
                    List.of()
            );
            when(resolver.resolve(CONNECTOR_VER_ID)).thenReturn(config);
            return resolver;
        }

        @Bean
        FrozenCredentialSetResolver frozenCredentialSetResolver() {
            FrozenCredentialSetResolver resolver = mock(FrozenCredentialSetResolver.class);
            when(resolver.resolve(eq(CREDENTIAL_SET_ID), any(), eq(HuashengOrderV1Metadata.OUTBOUND_KEYS)))
                    .thenAnswer(inv -> {
                        Map<String, char[]> copy = new HashMap<>();
                        copy.put(HuashengOrderV1Metadata.APP_SECRET, "test-secret".toCharArray());
                        copy.put(HuashengOrderV1Metadata.PRIVATE_KEY, "test-private-key".toCharArray());
                        return copy;
                    });
            return resolver;
        }

        @Bean
        FulfillmentOperationTicketFactory ticketFactory(HuashengCheckpointCodec codec) {
            return new FulfillmentOperationTicketFactory(
                    mock(CredentialSlotMapper.class), List.of(), new FulfillmentCallbackProperties(), List.of(), codec);
        }

        @Bean
        HuashengFulfillmentRunner huashengFulfillmentRunner(
                ReentrantLock lock,
                HuashengFulfillmentLifecycle lifecycle,
                HuashengOperationExecutor executor,
                FulfillmentOperationTicketFactory ticketFactory,
                FrozenCredentialSetResolver frozenCredentialSetResolver,
                PublishedConnectorConfigurationResolver connectorResolver,
                FulfillmentOperationMapper operationMapper,
                BOrderMapper bOrderMapper,
                FulfillmentRequestMapper requestMapper,
                SupplierMapper supplierMapper,
                SupplierOfferVersionMapper offerVersionMapper,
                SupplyChainDatabaseClock databaseClock) {
            return new HuashengFulfillmentRunner(
                    lock, lifecycle, executor, ticketFactory, frozenCredentialSetResolver, connectorResolver,
                    operationMapper, bOrderMapper, requestMapper, supplierMapper, offerVersionMapper, databaseClock);
        }

        @Bean
        HuashengExecuteTimerHandler huashengExecuteTimerHandler(org.springframework.beans.factory.ObjectProvider<HuashengFulfillmentRunner> runner) {
            return new HuashengExecuteTimerHandler(runner);
        }

        @Bean
        HuashengRecoveryTimerHandler huashengRecoveryTimerHandler(org.springframework.beans.factory.ObjectProvider<HuashengFulfillmentRunner> runner) {
            return new HuashengRecoveryTimerHandler(runner);
        }

        @Bean
        HuashengLeaseWatchdogHandler huashengLeaseWatchdogHandler(org.springframework.beans.factory.ObjectProvider<HuashengFulfillmentRunner> runner) {
            return new HuashengLeaseWatchdogHandler(runner);
        }

        @Bean
        HuashengPreparationWatchdogHandler huashengPreparationWatchdogHandler(org.springframework.beans.factory.ObjectProvider<HuashengFulfillmentRunner> runner) {
            return new HuashengPreparationWatchdogHandler(runner);
        }

        @Bean
        RechargeFrequencyLifecycle rechargeFrequencyLifecycle(
                com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyExecutionMapper executionMapper,
                com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyEntryMapper entryMapper,
                com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper queueMapper,
                SupplyChainDatabaseClock clock,
                SupplyChainOrderMapper orderMapper,
                BOrderMapper bOrderMapper,
                FulfillmentOperationMapper operationMapper) {
            return new RechargeFrequencyLifecycle(
                    queueMapper, entryMapper,
                    mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyIdentityService.class),
                    mock(com.aifuli.module.supplychain.tradecore.catalog.application.RechargeFrequencyProductResolver.class),
                    clock, executionMapper, operationMapper, bOrderMapper, orderMapper,
                    mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyWakeScheduler.class),
                    mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMigrationGateService.class)
            );
        }
    }
}
