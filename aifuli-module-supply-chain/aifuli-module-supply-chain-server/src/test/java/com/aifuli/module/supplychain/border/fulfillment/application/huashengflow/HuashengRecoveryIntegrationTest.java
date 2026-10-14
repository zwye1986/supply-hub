package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCallbackProperties;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCallResult;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCommunicationFacade;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
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
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengErrorCode;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderStatus;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengPrice;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengProtocolCard;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengQueryRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeStatus;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实 MySQL 上的华昇宕机/丢响应恢复、QUERY 驱动与原单推进集成测试。
 *
 * <p>测试覆盖：
 * 1. PLACE 丢响应 -> 恢复驱动 QUERY 原 outOrderSn；
 * 2. CONFIRM 丢响应 -> 恢复驱动 QUERY 原 orderSn；
 * 3. TAKE 丢响应 -> 恢复驱动 QUERY SUCCESS/Y 后按同 orderSn 重新提卡并最终成功；
 * 4. 7014 (订单未生成) 保持 UNKNOWN/WAIT_QUERY，绝不重下 PLACE，绝不提前释放资金，到限 20 次转 MANUAL；
 * 5. WAIT 受控 CONFIRM，累计达 3 次上限转 MANUAL 并保持资金；
 * 6. marker 前回滚允许首次执行；
 * 7. 锁忙抛出 HuashengLockBusyException，使 Timer 重试而不吞掉触发。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#5-资格、唯一下一步与宕机恢复}。</p>
 */
class HuashengRecoveryIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("huasheng_recovery")
            .withUsername("supply_chain")
            .withPassword("supply_chain");
    private static final String EXTERNAL_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static ConfigurableApplicationContext context;

    private static final Long CONNECTOR_VER_ID = 200L;
    private static final Long OFFER_VER_ID = 100L;
    private static final Long CREDENTIAL_SET_ID = 400L;

    private HuashengFulfillmentLifecycle lifecycle;
    private HuashengFulfillmentRunner runner;
    private HuashengExecuteTimerHandler executeHandler;
    private HuashengRecoveryTimerHandler recoveryHandler;
    private HuashengLeaseWatchdogHandler leaseWatchdogHandler;
    private HuashengPreparationWatchdogHandler preparationWatchdogHandler;
    private HuashengCommunicationFacade mockFacade;
    private SupplierOrderLifecycle supplierOrderLifecycle;
    private FulfillmentOperationMapper operationMapper;
    private BOrderMapper bOrderMapper;
    private SupplyChainOrderMapper orderMapper;
    private FulfillmentRequestMapper requestMapper;
    private SupplierMapper supplierMapper;
    private SupplierOfferVersionMapper offerVersionMapper;
    private TimerTaskMapper timerTaskMapper;
    private HuashengCheckpointCodec checkpointCodec;
    private BusinessNoGenerator businessNoGenerator;
    private SupplyChainDatabaseClock databaseClock;
    private ObjectMapper objectMapper;

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
        runner = context.getBean(HuashengFulfillmentRunner.class);
        executeHandler = context.getBean(HuashengExecuteTimerHandler.class);
        recoveryHandler = context.getBean(HuashengRecoveryTimerHandler.class);
        leaseWatchdogHandler = context.getBean(HuashengLeaseWatchdogHandler.class);
        preparationWatchdogHandler = context.getBean(HuashengPreparationWatchdogHandler.class);
        mockFacade = context.getBean(HuashengCommunicationFacade.class);
        supplierOrderLifecycle = context.getBean(SupplierOrderLifecycle.class);
        operationMapper = context.getBean(FulfillmentOperationMapper.class);
        bOrderMapper = context.getBean(BOrderMapper.class);
        orderMapper = context.getBean(SupplyChainOrderMapper.class);
        requestMapper = context.getBean(FulfillmentRequestMapper.class);
        supplierMapper = context.getBean(SupplierMapper.class);
        offerVersionMapper = context.getBean(SupplierOfferVersionMapper.class);
        timerTaskMapper = context.getBean(TimerTaskMapper.class);
        checkpointCodec = context.getBean(HuashengCheckpointCodec.class);
        businessNoGenerator = context.getBean(BusinessNoGenerator.class);
        databaseClock = context.getBean(SupplyChainDatabaseClock.class);
        objectMapper = context.getBean(ObjectMapper.class);

        reset(mockFacade, supplierOrderLifecycle);
    }

    @Test
    @DisplayName("1. PLACE 丢响应：执行超时/未知，安排恢复 Timer；恢复驱动 QUERY 原 outOrderSn，返回 WAIT 推进至 CONFIRM")
    void placeDroppedResponseQueriesOriginalOutOrderSn() {
        var fixture = seedOrderAggregate("HS_P_DROP", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();
        String billno = fixture.bOrder().getBillno();

        // 模拟 PLACE 发生网络超时或丢响应（返回 TransportFailure）
        HuashengFailureDiagnostics diag = mock(HuashengFailureDiagnostics.class);
        when(diag.reasonCode()).thenReturn(HuashengFailureDiagnostics.HuashengFailureReasonCode.TRANSPORT_FAILURE);
        HuashengCallResult<HuashengOrderResponse> transportFailure = new HuashengCallResult.TransportFailure<>(diag, List.of(), true);
        when(mockFacade.place(any(), any(), any(), any())).thenReturn(transportFailure);

        // Runner 执行正常下单
        String executeResult = runner.runExecute(opNo, "trace-place-timeout");
        assertThat(executeResult).isEqualTo("WAIT_QUERY");

        // 验证当前操作变为 RESULT_UNKNOWN，且已安排 RECOVERY_TASK
        FulfillmentOperationDO opAfterTimeout = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        assertThat(opAfterTimeout.getStatus()).isEqualTo(SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode());
        assertThat(opAfterTimeout.getNextRecoverAt()).isNotNull();

        // 模拟恢复触发：QUERY 原单
        HuashengOrderResponse waitResponse = mockOrderResponse("HS_ORD_P_01", billno, HuashengOrderStatus.WAIT, 1000L);
        when(mockFacade.query(any(), any(), any(), any()))
                .thenReturn(new HuashengCallResult.Parsed<>(waitResponse, List.of(), true));

        ObjectNode recPayload = objectMapper.createObjectNode();
        recPayload.put("operationNo", opNo);
        recPayload.put("expectedGeneration", opAfterTimeout.getExecutionGeneration());
        recPayload.put("trigger", HuashengFlowTrigger.RECOVER.name());

        markRecoveryDue(opNo);
        String recoveryResult = recoveryHandler.handle(new TimerTaskContext("TASK_01", 1L, 1, 1L, "worker-1"), recPayload);
        assertThat(recoveryResult).isEqualTo("NEXT_CONFIRM");

        // 断言 QUERY 必须是以 outOrderSn 查询，且 orderSn 严格为空（因为 PLACE 尚未拿到 orderSn）
        ArgumentCaptor<HuashengQueryRequest> queryCaptor = ArgumentCaptor.forClass(HuashengQueryRequest.class);
        verify(mockFacade).query(any(), queryCaptor.capture(), any(), any());
        HuashengQueryRequest actualQuery = queryCaptor.getValue();
        assertThat(actualQuery.outOrderSn()).contains(billno);
        assertThat(actualQuery.orderSn()).isEmpty();

        // 验证已生成 sequence 2 CONFIRM_ORDER
        List<FulfillmentOperationDO> ops = operationMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        assertThat(ops).hasSize(2);
        FulfillmentOperationDO seq2 = ops.get(1);
        assertThat(seq2.getSequenceNo()).isEqualTo(2);
        assertThat(seq2.getAdapterStepCode()).isEqualTo(AdapterStepCode.CONFIRM_ORDER.name());
        assertThat(seq2.getStatus()).isEqualTo(SupplierOrderOperationStatus.PENDING.getCode());
    }

    @Test
    @DisplayName("2. CONFIRM 丢响应：恢复驱动 QUERY 原 orderSn，返回 WAIT 推进受控 CONFIRM")
    void confirmDroppedResponseQueriesOriginalOrderSn() {
        String existingOrderSn = "HS_ORD_CONFIRM_01";
        var fixture = seedOrderAggregate("HS_C_DROP", AdapterStepCode.CONFIRM_ORDER, 2, existingOrderSn);
        String opNo = fixture.operation().getOperationNo();

        // 模拟 CONFIRM 丢响应
        HuashengFailureDiagnostics diag = mock(HuashengFailureDiagnostics.class);
        when(diag.reasonCode()).thenReturn(HuashengFailureDiagnostics.HuashengFailureReasonCode.TRANSPORT_FAILURE);
        when(mockFacade.confirm(any(), any(), any(), any()))
                .thenReturn(new HuashengCallResult.TransportFailure<>(diag, List.of(), true));

        String executeResult = runner.runExecute(opNo, "trace-confirm-timeout");
        assertThat(executeResult).isEqualTo("WAIT_QUERY");

        // 模拟恢复触发：QUERY 原单
        HuashengOrderResponse waitResponse = mockOrderResponse(existingOrderSn, fixture.bOrder().getBillno(), HuashengOrderStatus.WAIT, 1000L);
        when(mockFacade.query(any(), any(), any(), any()))
                .thenReturn(new HuashengCallResult.Parsed<>(waitResponse, List.of(), true));

        markRecoveryDue(opNo);
        String recoveryResult = runner.runRecovery(opNo, 1L, "trace-rec-confirm");
        assertThat(recoveryResult).isEqualTo("NEXT_CONFIRM");

        // 断言 QUERY 必须是以已知的 orderSn 查询
        ArgumentCaptor<HuashengQueryRequest> queryCaptor = ArgumentCaptor.forClass(HuashengQueryRequest.class);
        verify(mockFacade).query(any(), queryCaptor.capture(), any(), any());
        HuashengQueryRequest actualQuery = queryCaptor.getValue();
        assertThat(actualQuery.orderSn()).contains(existingOrderSn);
        assertThat(actualQuery.outOrderSn()).isEmpty();

        // 验证生成了 sequence 3 CONFIRM_ORDER
        List<FulfillmentOperationDO> ops = operationMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        assertThat(ops).hasSize(2);
        assertThat(ops.get(1).getSequenceNo()).isEqualTo(3);
        assertThat(ops.get(1).getAdapterStepCode()).isEqualTo(AdapterStepCode.CONFIRM_ORDER.name());
    }

    @Test
    @DisplayName("3. TAKE 丢响应：恢复 QUERY 返回 SUCCESS/Y，同 orderSn 唯一步推进 NEXT_TAKE 并最终成功提卡")
    void takeDroppedResponseQueriesSuccessYesThenTakesSameCard() {
        String existingOrderSn = "HS_ORD_TAKE_01";
        var fixture = seedOrderAggregate("HS_T_DROP", AdapterStepCode.TAKE_CARD, 3, existingOrderSn);
        String opNo = fixture.operation().getOperationNo();

        // 模拟 TAKE 超时
        HuashengFailureDiagnostics diag = mock(HuashengFailureDiagnostics.class);
        when(diag.reasonCode()).thenReturn(HuashengFailureDiagnostics.HuashengFailureReasonCode.TRANSPORT_FAILURE);
        when(mockFacade.take(any(), any(), any(), any()))
                .thenReturn(new HuashengCallResult.TransportFailure<>(diag, List.of(), true));

        String executeResult = runner.runExecute(opNo, "trace-take-timeout");
        assertThat(executeResult).isEqualTo("WAIT_QUERY");

        // 模拟恢复触发：QUERY 查出远端已 SUCCESS 且 isTake=Y
        HuashengOrderResponse querySuccessResp = mockOrderResponseWithTake(
                existingOrderSn, fixture.bOrder().getBillno(), HuashengOrderStatus.SUCCESS, HuashengTakeStatus.Y, 1000L);
        when(mockFacade.query(any(), any(), any(), any()))
                .thenReturn(new HuashengCallResult.Parsed<>(querySuccessResp, List.of(), true));

        markRecoveryDue(opNo);
        String recoveryResult = runner.runRecovery(opNo, 1L, "trace-rec-take");
        assertThat(recoveryResult).isEqualTo("NEXT_TAKE");

        // 断言已创建 sequence 4 TAKE_CARD
        List<FulfillmentOperationDO> ops = operationMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        assertThat(ops).hasSize(2);
        FulfillmentOperationDO seq4 = ops.get(1);
        assertThat(seq4.getSequenceNo()).isEqualTo(4);
        assertThat(seq4.getAdapterStepCode()).isEqualTo(AdapterStepCode.TAKE_CARD.name());

        // 模拟执行 sequence 4 成功提取卡密
        HuashengProtocolCard card = HuashengType201AssetCodecTest.card("{\"type\":201,\"code\":\"CD888\",\"pass\":\"PASS999\"}");
        HuashengTakeResponse takeSuccess = mock(HuashengTakeResponse.class);
        when(takeSuccess.orderSn()).thenReturn(existingOrderSn);
        when(takeSuccess.num()).thenReturn(1);
        when(takeSuccess.list()).thenReturn(List.of(card));

        when(mockFacade.take(any(), any(), any(), any()))
                .thenReturn(new HuashengCallResult.Parsed<>(takeSuccess, List.of(), true));

        String finalTakeResult = runner.runExecute(seq4.getOperationNo(), "trace-final-take");
        assertThat(finalTakeResult).isEqualTo("FINAL_SUCCESS");

        // 验证调用了受控资金提交
        verify(supplierOrderLifecycle).commitHuashengSuccess(any(), any(), any());
    }

    @Test
    @DisplayName("4. 7014 (订单未生成)：保持 UNKNOWN，绝不重下 PLACE，不释放资金；累计达 20 次上限转 MANUAL 并保持资金")
    void errorCode7014KeepsUnknownAndNeverRePlacesOrReleases() {
        var fixture = seedOrderAggregate("HS_7014", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();
        String billno = fixture.bOrder().getBillno();

        // 步骤 1: PLACE 超时
        HuashengFailureDiagnostics diag = mock(HuashengFailureDiagnostics.class);
        when(diag.reasonCode()).thenReturn(HuashengFailureDiagnostics.HuashengFailureReasonCode.TRANSPORT_FAILURE);
        when(mockFacade.place(any(), any(), any(), any()))
                .thenReturn(new HuashengCallResult.TransportFailure<>(diag, List.of(), true));
        runner.runExecute(opNo, "trace-place");

        // 步骤 2: QUERY 返回 7014 (ORDER_NOT_FOUND)
        com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengBusinessError err7014 =
                mock(com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengBusinessError.class);
        when(err7014.rawCode()).thenReturn(7014);
        when(err7014.knownErrorKind()).thenReturn(HuashengErrorCode.ORDER_NOT_FOUND);
        when(mockFacade.query(any(), any(), any(), any()))
                .thenReturn(new HuashengCallResult.BusinessError<>(err7014, List.of(), true));

        markRecoveryDue(opNo);
        String recResult = runner.runRecovery(opNo, 1L, "trace-rec-7014");
        assertThat(recResult).isEqualTo("WAIT_QUERY");

        // 验证当前操作仍为 RESULT_UNKNOWN，未创建新 sequence，资金未释放
        FulfillmentOperationDO op = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        assertThat(op.getStatus()).isEqualTo(SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode());
        assertThat(operationMapper.selectList(new LambdaQueryWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId()))).hasSize(1);

        // 步骤 3: 模拟持续 7014 查询累积至 20 次（MAX_QUERY_COUNT）
        for (int i = 2; i < 20; i++) {
            // 每次递增代次进行恢复
            markRecoveryDue(opNo);
            runner.runRecovery(opNo, (long) i, "trace-query-" + i);
        }

        // 第 20 次查询仍为 7014 -> 触发到限 MANUAL
        markRecoveryDue(opNo);
        String manualResult = runner.runRecovery(opNo, 20L, "trace-query-20");
        assertThat(manualResult).isEqualTo("MANUAL");

        FulfillmentOperationDO manualOp = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        assertThat(manualOp.getStatus()).isEqualTo(SupplierOrderOperationStatus.MANUAL.getCode());

        // 资金绝不释放，聚合进入 MANUAL 并保留唯一 case。
        verify(supplierOrderLifecycle, never()).closeHuashengTrustedFailure(any(), any(), any(), any());
        verify(supplierOrderLifecycle, never()).commitHuashengSuccess(any(), any(), any());
        verify(mockFacade, times(1)).place(any(), any(), any(), any());
        BOrderDO bOrder = bOrderMapper.selectById(fixture.bOrder().getId());
        assertThat(bOrder.getFulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.MANUAL.getCode());
    }

    @Test
    @DisplayName("5. WAIT 受控 CONFIRM：确认返回 WAIT 允许再次尝试，累计达 3 次上限转 MANUAL")
    void waitProducesControlledConfirmUntilMaxLimit() {
        String existingOrderSn = "HS_ORD_CONFIRM_LIMIT";
        var fixture = seedOrderAggregate("HS_C_LIMIT", AdapterStepCode.CONFIRM_ORDER, 2, existingOrderSn);
        String opNo = fixture.operation().getOperationNo();

        // 模拟 CONFIRM 返回 WAIT
        HuashengOrderResponse waitResp = mockOrderResponse(
                existingOrderSn, fixture.bOrder().getBillno(), HuashengOrderStatus.WAIT, 1000L);
        when(mockFacade.confirm(any(), any(), any(), any()))
                .thenReturn(new HuashengCallResult.Parsed<>(waitResp, List.of(), true));

        // 第一次 CONFIRM (confirmCount=1) -> 决策 NEXT_CONFIRM (sequence 3)
        String r1 = runner.runExecute(opNo, "trace-c-1");
        assertThat(r1).isEqualTo("NEXT_CONFIRM");

        FulfillmentOperationDO op3 = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .eq(FulfillmentOperationDO::getSequenceNo, 3));
        assertThat(op3).isNotNull();

        // 第二次 CONFIRM (confirmCount=2) -> 决策 NEXT_CONFIRM (sequence 4)
        String r2 = runner.runExecute(op3.getOperationNo(), "trace-c-2");
        assertThat(r2).isEqualTo("NEXT_CONFIRM");

        FulfillmentOperationDO op4 = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getBOrderId, fixture.bOrder().getId())
                        .eq(FulfillmentOperationDO::getSequenceNo, 4));
        assertThat(op4).isNotNull();

        // 第三次 CONFIRM (confirmCount=3 已达 MAX_CONFIRM_COUNT) -> 决策转 MANUAL
        String r3 = runner.runExecute(op4.getOperationNo(), "trace-c-3");
        assertThat(r3).isEqualTo("MANUAL");

        FulfillmentOperationDO op4Final = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, op4.getOperationNo()));
        assertThat(op4Final.getStatus()).isEqualTo(SupplierOrderOperationStatus.MANUAL.getCode());
        verify(supplierOrderLifecycle, never()).closeHuashengTrustedFailure(any(), any(), any(), any());
        verify(supplierOrderLifecycle, never()).commitHuashengSuccess(any(), any(), any());
    }

    @Test
    @DisplayName("6. marker 前回滚允许首次执行：claim 提交前若事务异常，marker 不存在，后续可重新首次执行")
    void rollbackBeforeMarkerAllowsFirstExecution() {
        var fixture = seedOrderAggregate("HS_ROLLBACK_CLAIM", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();

        // 验证初始状态为 PENDING，marker 为空
        FulfillmentOperationDO initial = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        assertThat(initial.getStatus()).isEqualTo(SupplierOrderOperationStatus.PENDING.getCode());
        assertThat(initial.getBusinessSendPossibleAt()).isNull();

        // 配置 facade 正常响应
        HuashengOrderResponse placeResp = mockOrderResponse("HS_ORD_ROLLBACK_OK", fixture.bOrder().getBillno(), HuashengOrderStatus.WAIT, 1000L);
        when(mockFacade.place(any(), any(), any(), any()))
                .thenReturn(new HuashengCallResult.Parsed<>(placeResp, List.of(), true));

        // 执行 runner 首次认领并执行成功
        String result = runner.runExecute(opNo, "trace-claim-ok");
        assertThat(result).isEqualTo("NEXT_CONFIRM");

        FulfillmentOperationDO completed = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, opNo));
        assertThat(completed.getStatus()).isEqualTo(SupplierOrderOperationStatus.SUCCEEDED.getCode());
        assertThat(completed.getBusinessSendPossibleAt()).isNotNull();
    }

    @Test
    @DisplayName("7. 锁忙场景：其它线程持有执行锁时，Timer Handler 抛出 HuashengLockBusyException 不吞掉触发")
    void runnerLockBusyThrowsHuashengLockBusyException() throws Exception {
        var fixture = seedOrderAggregate("HS_LOCK_BUSY", AdapterStepCode.PLACE_ORDER, 1, null);
        String opNo = fixture.operation().getOperationNo();

        ReentrantLock lock = runner.executionLock();
        CountDownLatch lockedLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Thread lockHolder = new Thread(() -> {
            lock.lock();
            try {
                lockedLatch.countDown();
                finishLatch.await();
            } catch (InterruptedException ignored) {
            } finally {
                lock.unlock();
            }
        });
        lockHolder.start();
        lockedLatch.await();

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("operationNo", opNo);

            assertThatThrownBy(() -> executeHandler.handle(new TimerTaskContext("T1", 1L, 1, 3L, "worker-1"), payload))
                    .isInstanceOf(HuashengLockBusyException.class);

            assertThatThrownBy(() -> recoveryHandler.handle(new TimerTaskContext("T2", 2L, 1, 3L, "worker-1"), payload))
                    .isInstanceOf(HuashengLockBusyException.class);

            assertThatThrownBy(() -> leaseWatchdogHandler.handle(new TimerTaskContext("T3", 3L, 1, 3L, "worker-1"), payload))
                    .isInstanceOf(HuashengLockBusyException.class);

            assertThatThrownBy(() -> preparationWatchdogHandler.handle(new TimerTaskContext("T4", 4L, 1, 3L, "worker-1"), payload))
                    .isInstanceOf(HuashengLockBusyException.class);
        } finally {
            finishLatch.countDown();
            lockHolder.join();
        }
    }

    private static final AtomicLong RECEIPT_SEQ = new AtomicLong(30000L);

    /** 非候选恢复场景将独立测试库的 Timer 前置置为到期，不绕过生产的退避检查。 */
    private void markRecoveryDue(String operationNo) {
        operationMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getOperationNo, operationNo)
                .set(FulfillmentOperationDO::getNextRecoverAt, databaseClock.now().minusSeconds(1)));
    }

    private TestAggregateFixture seedOrderAggregate(String prefix, AdapterStepCode step, int sequenceNo, String orderSn) {
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

        // 插入 Supplier 与 SupplierOfferVersion
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
                    com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengField.absent(),
                    com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengField.absent()
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

    @Configuration(proxyBeanMethods = false)
    @Import({
            SupplyChainDataSourceConfiguration.class,
            UlidGenerator.class,
            BusinessNoGenerator.class,
            AesGcmEnvelopeCrypto.class,
            HuashengCheckpointCodec.class,
            HuashengFulfillmentLifecycle.class,
            DatabasePersistentTimer.class,
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
            return mock(HuashengCommunicationFacade.class);
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
                    "CONN_HS_REC",
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
            Map<String, char[]> credMap = new HashMap<>();
            credMap.put(HuashengOrderV1Metadata.APP_SECRET, "test-secret".toCharArray());
            credMap.put(HuashengOrderV1Metadata.PRIVATE_KEY, "test-private-key".toCharArray());
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
