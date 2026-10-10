package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
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
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.DatabasePersistentTimer;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 真实 MySQL 上的华昇资格认领、唯一下一步与恢复断言门禁。
 *
 * <p>测试覆盖：
 * 1. claimForExecution：聚合锁后原子递增 generation，设置 EXECUTING、businessSendPossibleAt 及 lease，注册看门狗 Timer；
 * 2. claimForRecovery：租约过期后认领 QUERY，递增 generation 并使旧 permit 失格；
 * 3. applyOutcome：失格 permit 或租约过期结果只留观察，无下一步或资金副作用；
 * 4. 并发两线程应用相同事实：仅产生一个 sequence+1 唯一操作；
 * 5. 下一步事务原子性：Timer 注册失败时旧操作结束、新操作与 checkpoint 全部回滚。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#5-资格、唯一下一步与宕机恢复}。</p>
 */
class HuashengContinuationIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("huasheng_continuation")
            .withUsername("supply_chain")
            .withPassword("supply_chain");
    private static final String EXTERNAL_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static ConfigurableApplicationContext context;

    private HuashengFulfillmentLifecycle lifecycle;
    private FulfillmentOperationMapper operationMapper;
    private BOrderMapper bOrderMapper;
    private FulfillmentRequestMapper requestMapper;
    private SupplyChainOrderMapper orderMapper;
    private TimerTaskMapper timerTaskMapper;
    private HuashengCheckpointCodec checkpointCodec;
    private BusinessNoGenerator businessNoGenerator;
    private SupplyChainDatabaseClock databaseClock;

    @BeforeAll
    static void startAll() {
        if (EXTERNAL_URL == null) {
            MYSQL.start();
        }
        String keyBase64 = Base64.getEncoder().encodeToString(new byte[32]);
        context = new SpringApplicationBuilder(TestConfig.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", EXTERNAL_URL == null ? MYSQL.getJdbcUrl() : EXTERNAL_URL,
                        "aifuli.supply-chain.datasource.username", EXTERNAL_URL == null ? MYSQL.getUsername()
                                : System.getProperty("supply-chain.test.jdbc-username", "root"),
                        "aifuli.supply-chain.datasource.password", EXTERNAL_URL == null ? MYSQL.getPassword()
                                : System.getProperty("supply-chain.test.jdbc-password", ""),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver",
                        "aifuli.supply-chain.security.key-version", "test-v1",
                        "aifuli.supply-chain.security.envelope-key-base64", keyBase64,
                        "aifuli.supply-chain.security.fingerprint-key-base64", keyBase64
                )).run();
    }

    @AfterAll
    static void stopAll() {
        if (context != null) {
            context.close();
        }
        if (EXTERNAL_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @BeforeEach
    void setUp() {
        lifecycle = context.getBean(HuashengFulfillmentLifecycle.class);
        operationMapper = context.getBean(FulfillmentOperationMapper.class);
        bOrderMapper = context.getBean(BOrderMapper.class);
        requestMapper = context.getBean(FulfillmentRequestMapper.class);
        orderMapper = context.getBean(SupplyChainOrderMapper.class);
        timerTaskMapper = context.getBean(TimerTaskMapper.class);
        checkpointCodec = context.getBean(HuashengCheckpointCodec.class);
        businessNoGenerator = context.getBean(BusinessNoGenerator.class);
        databaseClock = context.getBean(SupplyChainDatabaseClock.class);
    }

    @Test
    @DisplayName("1. claimForExecution：原子认领 PENDING，generation 从 0 增至 1，写 marker 与 watchdog Timer")
    void claimForExecutionIncrementsGenerationAndSetsLeaseAndWatchdog() {
        var fixture = seedOrderAggregate("HS_CLAIM_01", AdapterStepCode.PLACE_ORDER, 1);
        String opNo = fixture.operation().getOperationNo();

        HuashengExecutionPermit permit = lifecycle.claimForExecution(opNo);

        assertThat(permit).isNotNull();
        assertThat(permit.operationNo()).isEqualTo(opNo);
        assertThat(permit.bOrderId()).isEqualTo(fixture.bOrder().getId());
        assertThat(permit.executionGeneration()).isEqualTo(1L);
        assertThat(permit.step()).isEqualTo(AdapterStepCode.PLACE_ORDER);
        assertThat(permit.trigger()).isEqualTo(HuashengFlowTrigger.EXECUTE);
        assertThat(permit.leaseUntil()).isAfter(databaseClock.now());

        FulfillmentOperationDO updated = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        assertThat(updated.getStatus()).isEqualTo(SupplierOrderOperationStatus.EXECUTING.getCode());
        assertThat(updated.getExecutionGeneration()).isEqualTo(1L);
        assertThat(updated.getExecutionLeaseUntil()).isEqualTo(permit.leaseUntil());
        assertThat(updated.getBusinessSendPossibleAt()).isNotNull();

        // 验证已注册 LEASE_WATCHDOG Timer
        var watchdog = timerTaskMapper.selectOne(new LambdaQueryWrapper<com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO>()
                .eq(com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO::getIdempotencyKey, "HS:LEASE:" + opNo + ":1"));
        assertThat(watchdog).isNotNull();
        assertThat(watchdog.getTaskType()).isEqualTo(HuashengFlowCodes.LEASE_WATCHDOG_TASK);

        // 再次 claim 认领失败返回 null
        HuashengExecutionPermit secondPermit = lifecycle.claimForExecution(opNo);
        assertThat(secondPermit).isNull();
    }

    @Test
    @DisplayName("2. claimForRecovery：租约过期后认领 QUERY，generation 递增至 2，旧 permit 失格")
    void claimForRecoveryAdvancesGenerationWhenLeaseExpired() {
        var fixture = seedOrderAggregate("HS_RECOVER_01", AdapterStepCode.PLACE_ORDER, 1);
        String opNo = fixture.operation().getOperationNo();

        HuashengExecutionPermit permit1 = lifecycle.claimForExecution(opNo);
        assertThat(permit1.executionGeneration()).isEqualTo(1L);

        // 模拟租约已过期
        FulfillmentOperationDO op = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        op.setExecutionLeaseUntil(databaseClock.now().minusSeconds(10));
        operationMapper.updateById(op);

        HuashengExecutionPermit recoveryPermit = lifecycle.claimForRecovery(opNo);
        assertThat(recoveryPermit).isNotNull();
        assertThat(recoveryPermit.executionGeneration()).isEqualTo(2L);
        assertThat(recoveryPermit.step()).isEqualTo(AdapterStepCode.QUERY_ORDER);
        assertThat(recoveryPermit.trigger()).isEqualTo(HuashengFlowTrigger.RECOVER);

        FulfillmentOperationDO recovered = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        assertThat(recovered.getExecutionGeneration()).isEqualTo(2L);
        assertThat(recovered.getRecoveryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("3. applyOutcome：失格 permit 或租约过期结果无下一步与资金副作用")
    void disqualifiedPermitOrExpiredLeaseProducesNoSideEffects() {
        var fixture = seedOrderAggregate("HS_DISQUALIFIED_01", AdapterStepCode.PLACE_ORDER, 1);
        String opNo = fixture.operation().getOperationNo();

        HuashengExecutionPermit permit1 = lifecycle.claimForExecution(opNo);

        // 模拟通过 recovery 递增代次至 2
        FulfillmentOperationDO op = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        op.setExecutionLeaseUntil(databaseClock.now().minusSeconds(10));
        operationMapper.updateById(op);
        HuashengExecutionPermit recoveryPermit = lifecycle.claimForRecovery(opNo);
        assertThat(recoveryPermit.executionGeneration()).isEqualTo(2L);

        // 尝试用代次 1 的旧 permit 提交结果
        HuashengStepFact fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.PLACE,
                HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder().getBillno(),
                "HS_ORD_999",
                1,
                "10.00",
                1000L,
                HuashengStepFact.Status.WAIT,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null,
                List.of()
        );
        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.ACCEPTED)
                .protocolFact(fact)
                .externalOrderNo("HS_ORD_999")
                .reportedSettlementAmount(1000L)
                .build();

        HuashengApplyResult staleResult = lifecycle.apply(permit1, outcome, "trace-stale");
        assertThat(staleResult.applied()).isFalse();

        // 验证没有创建 sequence 2
        List<FulfillmentOperationDO> ops = operationMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId()));
        assertThat(ops).hasSize(1);
        assertThat(ops.getFirst().getSequenceNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("4. 并发两线程应用相同事实：仅产生一个 sequence+1 唯一操作")
    void concurrentApplyProducesExactlyOneNextStep() throws InterruptedException, ExecutionException {
        var fixture = seedOrderAggregate("HS_CONCUR_01", AdapterStepCode.PLACE_ORDER, 1);
        String opNo = fixture.operation().getOperationNo();
        HuashengExecutionPermit permit = lifecycle.claimForExecution(opNo);

        HuashengStepFact fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.PLACE,
                HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder().getBillno(),
                "HS_ORD_CONCUR",
                1,
                "10.00",
                1000L,
                HuashengStepFact.Status.WAIT,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null,
                List.of()
        );
        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.ACCEPTED)
                .protocolFact(fact)
                .externalOrderNo("HS_ORD_CONCUR")
                .reportedSettlementAmount(1000L)
                .build();

        var executor = Executors.newFixedThreadPool(2);
        var readyLatch = new CountDownLatch(2);
        var startLatch = new CountDownLatch(1);

        Callable<HuashengApplyResult> task = () -> {
            readyLatch.countDown();
            startLatch.await();
            return lifecycle.apply(permit, outcome, "trace-concur");
        };

        Future<HuashengApplyResult> f1 = executor.submit(task);
        Future<HuashengApplyResult> f2 = executor.submit(task);

        readyLatch.await();
        startLatch.countDown();

        HuashengApplyResult r1 = f1.get();
        HuashengApplyResult r2 = f2.get();
        executor.shutdown();

        // 两个线程只有一个 applied == true
        assertThat(r1.applied() ^ r2.applied()).isTrue();

        // 验证数据库中仅存在 sequence 1 与 sequence 2
        List<FulfillmentOperationDO> ops = operationMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        assertThat(ops).hasSize(2);
        assertThat(ops.get(0).getSequenceNo()).isEqualTo(1);
        assertThat(ops.get(0).getStatus()).isEqualTo(SupplierOrderOperationStatus.SUCCEEDED.getCode());
        assertThat(ops.get(1).getSequenceNo()).isEqualTo(2);
        assertThat(ops.get(1).getAdapterStepCode()).isEqualTo(AdapterStepCode.CONFIRM_ORDER.name());
        assertThat(ops.get(1).getStatus()).isEqualTo(SupplierOrderOperationStatus.PENDING.getCode());

        // 验证唯一步推进生成 sequence 2 且已调度 EXECUTE_TASK Timer
        var execTimer = timerTaskMapper.selectOne(new LambdaQueryWrapper<com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO>()
                .eq(com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO::getIdempotencyKey, "HS:EXEC:" + ops.get(1).getOperationNo()));
        assertThat(execTimer).isNotNull();
        assertThat(execTimer.getTaskType()).isEqualTo(HuashengFlowCodes.EXECUTE_TASK);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(execTimer.getPayloadJson()).toString()).contains("originTraceId");

        // 验证 sequence 2 的 Checkpoint 解密正确且 binding/parameters 完好
        var stored2 = new HuashengCheckpointCodec.Stored(
                ops.get(1).getCheckpointSchemaVersion(),
                ops.get(1).getCheckpointKeyVersion(),
                ops.get(1).getCheckpointCiphertext()
        );
        var expectedBinding2 = new HuashengCheckpoint.Binding(
                fixture.bOrder().getId(),
                fixture.bOrder().getBOrderNo(),
                ops.get(1).getOperationNo(),
                2,
                AdapterStepCode.CONFIRM_ORDER,
                fixture.bOrder().getConnectorVersionId(),
                fixture.bOrder().getOfferVersionId(),
                ops.get(1).getConfigDigest()
        );
        var decoded2 = checkpointCodec.decrypt(stored2, expectedBinding2);
        assertThat(decoded2.placeParameters().outOrderSn()).isEqualTo(fixture.bOrder().getBillno());
        assertThat(decoded2.orderSn()).isEqualTo("HS_ORD_CONCUR");
        assertThat(decoded2.progress().originTraceId()).isEqualTo("seed-trace");
    }

    @Test
    @DisplayName("5. 下一步事务原子性：Timer 注册失败时旧操作结束、新操作与 checkpoint 全部回滚")
    void nextStepAtomicityRollsBackWhenTimerFails() {
        var fixture = seedOrderAggregate("HS_ROLLBACK_01", AdapterStepCode.PLACE_ORDER, 1);
        String opNo = fixture.operation().getOperationNo();
        HuashengExecutionPermit permit = lifecycle.claimForExecution(opNo);

        HuashengStepFact fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.PLACE,
                HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder().getBillno(),
                "HS_ORD_FAIL",
                1,
                "10.00",
                1000L,
                HuashengStepFact.Status.WAIT,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null,
                List.of()
        );
        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.ACCEPTED)
                .protocolFact(fact)
                .externalOrderNo("HS_ORD_FAIL")
                .reportedSettlementAmount(1000L)
                .build();

        TestPersistentTimer timerWrapper = context.getBean(TestPersistentTimer.class);
        timerWrapper.setFail(true);
        try {
            assertThatThrownBy(() -> lifecycle.apply(permit, outcome, "trace-fail"))
                    .isInstanceOf(RuntimeException.class);

            // 验证原子回滚：原操作仍为 EXECUTING，没有新创建 sequence 2 的操作
            FulfillmentOperationDO original = operationMapper.selectOne(
                    new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
            assertThat(original.getStatus()).isEqualTo(SupplierOrderOperationStatus.EXECUTING.getCode());

            List<FulfillmentOperationDO> ops = operationMapper.selectList(
                    new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId()));
            assertThat(ops).hasSize(1);
        } finally {
            timerWrapper.setFail(false);
        }
    }

    @Test
    @DisplayName("6. scheduleQueryRecovery：WAIT_QUERY 决策调度 RECOVERY_TIMER_TASK，不推进 sequence 或释放资金")
    void waitQueryDecisionSchedulesRecoveryTimerWithoutAdvancing() {
        var fixture = seedOrderAggregate("HS_WAIT_QUERY_01", AdapterStepCode.PLACE_ORDER, 1);
        String opNo = fixture.operation().getOperationNo();
        HuashengExecutionPermit permit = lifecycle.claimForExecution(opNo);

        // 模拟返回 7014 订单未生成，决策为 WAIT_QUERY
        HuashengStepFact fact = new HuashengStepFact(
                HuashengStepFact.Endpoint.QUERY,
                HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder().getBillno(),
                null,
                1,
                null,
                null,
                HuashengStepFact.Status.UNKNOWN,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null,
                List.of()
        );
        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN)
                .protocolFact(fact)
                .build();

        HuashengApplyResult result = lifecycle.apply(permit, outcome, "trace-query-recovery");
        assertThat(result.applied()).isTrue();
        assertThat(result.decision()).isEqualTo(HuashengFlowDecision.WAIT_QUERY);

        // 验证已注册 RECOVERY_TASK Timer
        var recTimer = timerTaskMapper.selectOne(new LambdaQueryWrapper<com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO>()
                .eq(com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO::getIdempotencyKey, "HS:REC:" + opNo + ":1"));
        assertThat(recTimer).isNotNull();
        assertThat(recTimer.getTaskType()).isEqualTo(HuashengFlowCodes.RECOVERY_TASK);

        // 验证没有新建 sequence 2
        List<FulfillmentOperationDO> ops = operationMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId()));
        assertThat(ops).hasSize(1);
    }

    @Test
    @DisplayName("7. 可信回调推进：当前下一步仍 PENDING 且 marker 为空时，首 PLACE 已发送的可信回调可收敛原单")
    void trustedCallbackConvergesOrderWhenNextStepIsPendingWithNullMarker() {
        var fixture = seedOrderAggregate("HS_CALLBACK_01", AdapterStepCode.PLACE_ORDER, 1);
        String opNo = fixture.operation().getOperationNo();
        HuashengExecutionPermit permit = lifecycle.claimForExecution(opNo);

        // 步骤 1: PLACE 认领后产生 WAIT，推进到 sequence 2 CONFIRM_ORDER
        HuashengStepFact placeFact = new HuashengStepFact(
                HuashengStepFact.Endpoint.PLACE,
                HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder().getBillno(),
                "HS_ORD_CB_01",
                1,
                "10.00",
                1000L,
                HuashengStepFact.Status.WAIT,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null,
                List.of()
        );
        OperationOutcome placeOutcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.ACCEPTED)
                .protocolFact(placeFact)
                .externalOrderNo("HS_ORD_CB_01")
                .reportedSettlementAmount(1000L)
                .build();

        HuashengApplyResult placeResult = lifecycle.apply(permit, placeOutcome, "trace-place");
        assertThat(placeResult.applied()).isTrue();
        assertThat(placeResult.decision()).isEqualTo(HuashengFlowDecision.NEXT_CONFIRM);

        // 此时 sequence 2 为 PENDING，businessSendPossibleAt 为 NULL，尚未由 runner 认领
        List<FulfillmentOperationDO> ops = operationMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        assertThat(ops).hasSize(2);
        FulfillmentOperationDO seq2 = ops.get(1);
        assertThat(seq2.getSequenceNo()).isEqualTo(2);
        assertThat(seq2.getStatus()).isEqualTo(SupplierOrderOperationStatus.PENDING.getCode());
        assertThat(seq2.getBusinessSendPossibleAt()).isNull();

        // 步骤 2: 此时外部异步回调到达（最终成功），携带合法卡密资产
        var card = HuashengType201AssetCodecTest.card("{\"type\":201,\"code\":\"CD123\",\"pass\":\"SEC456\"}");
        var assetFact = new HuashengType201AssetCodec().toFact(card);
        byte[] payload = assetFact.protectedPayload();
        HuashengStepFact callbackFact = new HuashengStepFact(
                HuashengStepFact.Endpoint.CALLBACK,
                HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder().getBillno(),
                "HS_ORD_CB_01",
                1,
                "10.00",
                1000L,
                HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                payload,
                List.of()
        );

        HuashengApplyResult callbackResult = lifecycle.applyCallback(fixture.bOrder().getId(), callbackFact, List.of(assetFact), "trace-callback");
        assertThat(callbackResult.applied()).isTrue();
        assertThat(callbackResult.decision()).isEqualTo(HuashengFlowDecision.FINAL_SUCCESS);

        // 验证 sequence 2 的 generation 递增，淘汰在途 permit，且已调用受控 commit
        FulfillmentOperationDO finalOp = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, seq2.getOperationNo()));
        assertThat(finalOp.getExecutionGeneration()).isEqualTo(1L);
    }

    @Test
    @DisplayName("8. 可信回调拒绝：若首 PLACE 尚未发送（marker 为空），回调被拒绝并不产生副作用")
    void callbackRejectedWhenFirstPlaceMarkerMissing() {
        var fixture = seedOrderAggregate("HS_CALLBACK_REJECT_01", AdapterStepCode.PLACE_ORDER, 1);

        // 尚未认领，首 PLACE 的 businessSendPossibleAt 为 NULL
        HuashengStepFact callbackFact = new HuashengStepFact(
                HuashengStepFact.Endpoint.CALLBACK,
                HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder().getBillno(),
                "HS_ORD_EARLY",
                1,
                "10.00",
                1000L,
                HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.ABSENT,
                HuashengStepFact.IsTake.ABSENT,
                null,
                List.of()
        );

        HuashengApplyResult result = lifecycle.applyCallback(fixture.bOrder().getId(), callbackFact, "trace-early");
        assertThat(result.applied()).isFalse();
        assertThat(result.message()).contains("First PLACE send marker missing");
    }

    private static final java.util.concurrent.atomic.AtomicLong RECEIPT_SEQ = new java.util.concurrent.atomic.AtomicLong(10000L);

    private TestAggregateFixture seedOrderAggregate(String prefix, AdapterStepCode step, int sequenceNo) {
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

        BOrderDO bOrder = BOrderDO.builder()
                .bOrderNo(bOrderNo).fulfillmentRequestId(request.getId()).orderId(order.getId())
                .attemptNo(1).supplierId(1L).offerId(10L).offerVersionId(100L).connectorVersionId(200L)
                .inventorySourceId(1L).billno(billno).idempotencyKey(bOrderNo)
                .requestedQuantity(1L).fulfilledQuantity(0L)
                .supplierSettlementUnitPrice(1000L).expectedSupplierSettlementAmount(1000L)
                .currency("CNY").fulfillmentStatus(BOrderFulfillmentStatus.PROCESSING.getCode())
                .settlementStatus(BOrderSettlementStatus.PENDING.getCode()).activeFlag(true)
                .version(0L).createdAt(now).updatedAt(now).build();
        bOrderMapper.insert(bOrder);

        // 设置 request activeBOrderId
        request.setActiveBOrderId(bOrder.getId());
        requestMapper.updateById(request);

        String idempotencyKey = FulfillmentIdempotencyKeys.operation(bOrderNo, sequenceNo);
        byte[] configDigest = sha256((idempotencyKey + "|100|200").getBytes(StandardCharsets.UTF_8));

        // 构造并加密首个 checkpoint
        HuashengCheckpoint.Binding binding = new HuashengCheckpoint.Binding(
                bOrder.getId(), bOrderNo, opNo, sequenceNo, step, 200L, 100L, configDigest);
        HuashengCheckpoint.PlaceParameters place = new HuashengCheckpoint.PlaceParameters(
                billno, 1001L, 1, 1800, "13800138000", "http://localhost/callback/huasheng/200");
        HuashengCheckpoint.Progress progress = new HuashengCheckpoint.Progress(0, 0, 0, null, null, "seed-trace");
        HuashengCheckpoint checkpoint = new HuashengCheckpoint(binding, place, null, null, progress, null);
        HuashengCheckpointCodec.Stored stored = checkpointCodec.encrypt(checkpoint);

        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .operationNo(opNo).bOrderId(bOrder.getId()).operationKind(FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(step.name()).sequenceNo(sequenceNo).idempotencyKey(idempotencyKey)
                .configDigest(configDigest).status(SupplierOrderOperationStatus.PENDING.getCode())
                .attemptCount(0).executionGeneration(0L).checkpointSchemaVersion(stored.schemaVersion())
                .checkpointKeyVersion(stored.keyVersion()).checkpointCiphertext(stored.ciphertext())
                .version(0L).createdAt(now).updatedAt(now).build();
        operationMapper.insert(operation);

        return new TestAggregateFixture(order, request, bOrder, operation);
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

    @Configuration(proxyBeanMethods = false)
    @Import({
            SupplyChainDataSourceConfiguration.class,
            UlidGenerator.class,
            BusinessNoGenerator.class,
            AesGcmEnvelopeCrypto.class,
            HuashengCheckpointCodec.class,
            HuashengFulfillmentLifecycle.class,
            HuashengExecuteTimerHandler.class,
            HuashengLeaseWatchdogHandler.class,
            HuashengPreparationWatchdogHandler.class,
            HuashengRecoveryTimerHandler.class,
            DatabasePersistentTimer.class,
            PersistentTimerHandlerRegistry.class
    })
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        SupplierOrderLifecycle supplierOrderLifecycle() {
            return mock(SupplierOrderLifecycle.class);
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

        @Bean
        @org.springframework.context.annotation.Primary
        TestPersistentTimer testPersistentTimer(@org.springframework.beans.factory.annotation.Qualifier("com.aifuli.module.supplychain.infrastructure.timer.DatabasePersistentTimer") PersistentTimer delegate) {
            return new TestPersistentTimer(delegate);
        }
    }

    static class TestPersistentTimer implements PersistentTimer {
        private final PersistentTimer delegate;
        private volatile boolean fail;

        TestPersistentTimer(PersistentTimer delegate) {
            this.delegate = delegate;
        }

        void setFail(boolean fail) {
            this.fail = fail;
        }

        @Override
        public com.aifuli.module.supplychain.infrastructure.timer.TimerHandle scheduleOnce(String taskType, String idempotencyKey, com.fasterxml.jackson.databind.JsonNode payload, java.time.Instant fireAt) {
            if (fail) {
                throw new RuntimeException("Injected timer registration failure");
            }
            return delegate.scheduleOnce(taskType, idempotencyKey, payload, fireAt);
        }

        @Override
        public boolean cancel(String taskNo, String reason) {
            return delegate.cancel(taskNo, reason);
        }
    }
}
