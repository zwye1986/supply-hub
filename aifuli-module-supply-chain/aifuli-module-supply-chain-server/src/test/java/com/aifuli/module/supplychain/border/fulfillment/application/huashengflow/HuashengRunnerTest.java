package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSetResolver;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 华昇生产履约 Runner 单元测试。
 *
 * <p>测试目标：
 * 1. 互斥锁在 claim 前尝试，覆盖 claim/HTTP/apply，忙态不占资格抛出 HuashengLockBusyException；
 * 2. finally 正常、异常或中断均释放互斥锁；
 * 3. 同一进程不同冻结版本共用一把锁，互斥外呼；
 * 4. MDC 保存并恢复原快照，生成新 traceId/executionId，绝不用 taskId 冒充 traceId，携带 originTraceId；
 * 5. 凭据材料在 finally 中擦除。
 */
@ExtendWith(MockitoExtension.class)
class HuashengRunnerTest {

    private ReentrantLock executionLock;

    @Mock
    private HuashengFulfillmentLifecycle lifecycle;
    @Mock
    private HuashengOperationExecutor executor;
    @Mock
    private FulfillmentOperationTicketFactory ticketFactory;
    @Mock
    private FrozenCredentialSetResolver frozenCredentialSetResolver;
    @Mock
    private PublishedConnectorConfigurationResolver connectorResolver;
    @Mock
    private FulfillmentOperationMapper operationMapper;
    @Mock
    private BOrderMapper bOrderMapper;
    @Mock
    private FulfillmentRequestMapper requestMapper;
    @Mock
    private SupplierMapper supplierMapper;
    @Mock
    private SupplierOfferVersionMapper offerVersionMapper;
    @Mock
    private SupplyChainDatabaseClock databaseClock;

    private HuashengFulfillmentRunner runner;

    private static final String OP_NO = "OP_1001";
    private static final Long B_ORDER_ID = 2001L;
    private static final Long CONNECTOR_VER_ID = 3001L;
    private static final Long CREDENTIAL_SET_ID = 4001L;

    @BeforeEach
    void setUp() {
        executionLock = new ReentrantLock();
        runner = new HuashengFulfillmentRunner(
                executionLock,
                lifecycle,
                executor,
                ticketFactory,
                frozenCredentialSetResolver,
                connectorResolver,
                operationMapper,
                bOrderMapper,
                requestMapper,
                supplierMapper,
                offerVersionMapper,
                databaseClock
        );
    }

    private void mockBasicEntities() {
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(100L)
                .operationNo(OP_NO)
                .bOrderId(B_ORDER_ID)
                .status(SupplierOrderOperationStatus.PENDING.getCode())
                .adapterStepCode(AdapterStepCode.PLACE_ORDER.name())
                .executionGeneration(0L)
                .build();
        lenient().when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        lenient().when(operationMapper.selectById(100L)).thenReturn(operation);

        BOrderDO bOrder = BOrderDO.builder()
                .id(B_ORDER_ID)
                .bOrderNo("BO_1001")
                .billno("BILL_1001")
                .connectorVersionId(CONNECTOR_VER_ID)
                .fulfillmentRequestId(5001L)
                .supplierId(6001L)
                .offerVersionId(7001L)
                .activeFlag(true)
                .build();
        lenient().when(bOrderMapper.selectById(B_ORDER_ID)).thenReturn(bOrder);

        PublishedConnectorConfiguration connectorConfig = new PublishedConnectorConfiguration(
                "CONN_001",
                CONNECTOR_VER_ID,
                1,
                HuashengOrderV1Metadata.TYPE,
                "https://api.huasheng.com",
                "APP_ID_16_BYTES_",
                null,
                "/callbacks/suppliers/huasheng",
                java.util.Set.of(com.aifuli.module.supplychain.supplieradapter.metadata.Capability.ISSUE_ASSET),
                CREDENTIAL_SET_ID,
                com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.CREDENTIAL_SET,
                10000,
                10000,
                Map.of(),
                List.of()
        );
        lenient().when(connectorResolver.resolve(CONNECTOR_VER_ID)).thenReturn(connectorConfig);

        lenient().when(requestMapper.selectById(any())).thenReturn(FulfillmentRequestDO.builder().id(5001L).build());
        lenient().when(supplierMapper.selectById(any())).thenReturn(SupplierDO.builder().id(6001L).supplierCode("HS_SUPP").build());
        lenient().when(offerVersionMapper.selectById(any())).thenReturn(SupplierOfferVersionDO.builder().id(7001L).build());
    }

    @Test
    @DisplayName("互斥锁在 claim 前尝试并覆盖全链路，执行完毕在 finally 释放锁")
    void lockAcquiredBeforeClaimAndReleasedInFinally() {
        mockBasicEntities();

        Map<String, char[]> creds = new HashMap<>();
        creds.put(HuashengOrderV1Metadata.APP_SECRET, "test-secret".toCharArray());
        creds.put(HuashengOrderV1Metadata.PRIVATE_KEY, "test-key".toCharArray());
        when(frozenCredentialSetResolver.resolve(eq(CREDENTIAL_SET_ID), any(), eq(HuashengOrderV1Metadata.OUTBOUND_KEYS)))
                .thenReturn(creds);

        HuashengExecutionPermit permit = new HuashengExecutionPermit(
                OP_NO, B_ORDER_ID, 1L, AdapterStepCode.PLACE_ORDER,
                HuashengFlowTrigger.EXECUTE, LocalDateTime.now().plusSeconds(150)
        );
        AtomicBoolean lockHeldDuringClaim = new AtomicBoolean(false);
        when(lifecycle.claimForExecution(OP_NO)).thenAnswer(inv -> {
            lockHeldDuringClaim.set(executionLock.isHeldByCurrentThread());
            return permit;
        });

        OperationTicket ticket = mock(OperationTicket.class);
        when(ticketFactory.createForHuasheng(any(), any(), any(), any(), any(), any(), eq(permit), any(), any()))
                .thenReturn(ticket);

        OperationOutcome outcome = OperationOutcome.builder().outcomeClass(OperationOutcomeClass.ACCEPTED).build();
        when(executor.execute(ticket)).thenReturn(outcome);

        HuashengApplyResult applyResult = new HuashengApplyResult(HuashengFlowDecision.NEXT_CONFIRM, "OP_1002", true, "ok");
        when(lifecycle.apply(eq(permit), eq(outcome), any())).thenReturn(applyResult);

        String result = runner.runExecute(OP_NO, "origin-trace-123");

        assertThat(result).isEqualTo(HuashengFlowDecision.NEXT_CONFIRM.name());
        assertThat(lockHeldDuringClaim.get()).isTrue();
        assertThat(executionLock.isLocked()).isFalse();
    }

    @Test
    @DisplayName("互斥锁忙态不占资格，抛出可重试异常且不调用 claim")
    void lockBusyThrowsExceptionWithoutClaiming() throws Exception {
        java.util.concurrent.CountDownLatch lockedLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finishLatch = new java.util.concurrent.CountDownLatch(1);
        Thread lockHolder = new Thread(() -> {
            executionLock.lock();
            try {
                lockedLatch.countDown();
                finishLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                executionLock.unlock();
            }
        });
        lockHolder.start();
        lockedLatch.await();

        try {
            assertThatThrownBy(() -> runner.runExecute(OP_NO, "origin-trace-123"))
                    .isInstanceOf(HuashengLockBusyException.class)
                    .hasMessageContaining("lock is busy");

            verify(lifecycle, never()).claimForExecution(any());
            verify(executor, never()).execute(any());
        } finally {
            finishLatch.countDown();
            lockHolder.join();
        }
    }

    @Test
    @DisplayName("执行过程中抛出异常时，finally 依然保证释放互斥锁")
    void exceptionInExecutionReleasesLock() {
        mockBasicEntities();

        Map<String, char[]> creds = new HashMap<>();
        creds.put(HuashengOrderV1Metadata.APP_SECRET, "test-secret".toCharArray());
        creds.put(HuashengOrderV1Metadata.PRIVATE_KEY, "test-key".toCharArray());
        when(frozenCredentialSetResolver.resolve(eq(CREDENTIAL_SET_ID), any(), eq(HuashengOrderV1Metadata.OUTBOUND_KEYS)))
                .thenReturn(creds);

        when(lifecycle.claimForExecution(OP_NO)).thenThrow(new RuntimeException("DB claim failed"));

        assertThatThrownBy(() -> runner.runExecute(OP_NO, "origin-trace-123"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB claim failed");

        assertThat(executionLock.isLocked()).isFalse();
    }

    @Test
    @DisplayName("MDC 隔离：生成新 traceId/executionId，携带 originTraceId，绝不用 taskId 冒充，finally 恢复原 MDC")
    void mdcIsolatedAndRestoredInFinally() {
        mockBasicEntities();

        Map<String, char[]> creds = new HashMap<>();
        creds.put(HuashengOrderV1Metadata.APP_SECRET, "test-secret".toCharArray());
        creds.put(HuashengOrderV1Metadata.PRIVATE_KEY, "test-key".toCharArray());
        when(frozenCredentialSetResolver.resolve(eq(CREDENTIAL_SET_ID), any(), eq(HuashengOrderV1Metadata.OUTBOUND_KEYS)))
                .thenReturn(creds);

        HuashengExecutionPermit permit = new HuashengExecutionPermit(
                OP_NO, B_ORDER_ID, 1L, AdapterStepCode.PLACE_ORDER,
                HuashengFlowTrigger.EXECUTE, LocalDateTime.now().plusSeconds(150)
        );
        when(lifecycle.claimForExecution(OP_NO)).thenReturn(permit);

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedExecutionId = new AtomicReference<>();
        AtomicReference<String> capturedOriginTraceId = new AtomicReference<>();

        when(ticketFactory.createForHuasheng(any(), any(), any(), any(), any(), any(), eq(permit), any(), any()))
                .thenAnswer(inv -> {
                    capturedTraceId.set(MDC.get("traceId"));
                    capturedExecutionId.set(MDC.get("executionId"));
                    capturedOriginTraceId.set(MDC.get("originTraceId"));
                    return mock(OperationTicket.class);
                });

        OperationOutcome outcome = OperationOutcome.builder().outcomeClass(OperationOutcomeClass.ACCEPTED).build();
        when(executor.execute(any())).thenReturn(outcome);
        when(lifecycle.apply(any(), any(), any())).thenReturn(
                new HuashengApplyResult(HuashengFlowDecision.NEXT_CONFIRM, "OP_1002", true, "ok"));

        MDC.put("test_prev_key", "prev_value");
        try {
            runner.runExecute(OP_NO, "ORIGIN_TRACE_777");

            assertThat(capturedTraceId.get()).isNotNull().hasSize(32).isNotEqualTo("ORIGIN_TRACE_777");
            assertThat(capturedExecutionId.get()).isNotNull().hasSize(32);
            assertThat(capturedOriginTraceId.get()).isEqualTo("ORIGIN_TRACE_777");

            // 退出后 MDC 必须严格恢复
            assertThat(MDC.get("test_prev_key")).isEqualTo("prev_value");
            assertThat(MDC.get("traceId")).isNull();
            assertThat(MDC.get("executionId")).isNull();
            assertThat(MDC.get("originTraceId")).isNull();
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("凭据材料在 finally 中被清零擦除")
    void credentialMaterialWipedInFinally() {
        mockBasicEntities();

        char[] secretChars = "super-secret".toCharArray();
        char[] keyChars = "private-key".toCharArray();
        Map<String, char[]> creds = new HashMap<>();
        creds.put(HuashengOrderV1Metadata.APP_SECRET, secretChars);
        creds.put(HuashengOrderV1Metadata.PRIVATE_KEY, keyChars);
        when(frozenCredentialSetResolver.resolve(eq(CREDENTIAL_SET_ID), any(), eq(HuashengOrderV1Metadata.OUTBOUND_KEYS)))
                .thenReturn(creds);

        HuashengExecutionPermit permit = new HuashengExecutionPermit(
                OP_NO, B_ORDER_ID, 1L, AdapterStepCode.PLACE_ORDER,
                HuashengFlowTrigger.EXECUTE, LocalDateTime.now().plusSeconds(150)
        );
        when(lifecycle.claimForExecution(OP_NO)).thenReturn(permit);

        OperationTicket ticket = mock(OperationTicket.class);
        when(ticketFactory.createForHuasheng(any(), any(), any(), any(), any(), any(), eq(permit), any(), any()))
                .thenReturn(ticket);
        when(executor.execute(ticket)).thenReturn(OperationOutcome.builder().outcomeClass(OperationOutcomeClass.ACCEPTED).build());
        when(lifecycle.apply(any(), any(), any())).thenReturn(
                new HuashengApplyResult(HuashengFlowDecision.NEXT_CONFIRM, "OP_1002", true, "ok"));

        runner.runExecute(OP_NO, "origin-trace-123");

        // 断言 char[] 数组已被零化
        for (char c : secretChars) {
            assertThat(c).isEqualTo('\0');
        }
        for (char c : keyChars) {
            assertThat(c).isEqualTo('\0');
        }
    }

    @Test
    @DisplayName("恢复调用：旧 generation 迟到返回 OBSOLETE_GENERATION，不执行 claim 与外呼")
    void runRecoverySkipsObsoleteGeneration() {
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(100L)
                .operationNo(OP_NO)
                .bOrderId(B_ORDER_ID)
                .status(SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode())
                .adapterStepCode(AdapterStepCode.PLACE_ORDER.name())
                .executionGeneration(5L)
                .build();
        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);

        String result = runner.runRecovery(OP_NO, 4L, "origin-123");

        assertThat(result).isEqualTo("OBSOLETE_GENERATION");
        verify(lifecycle, never()).claimForRecovery(any());
        verify(executor, never()).execute(any());
    }

    @Test
    @DisplayName("租约看门狗：租约未到期返回 LEASE_NOT_EXPIRED，不驱动恢复")
    void runLeaseWatchdogSkipsUnexpiredLease() {
        LocalDateTime now = LocalDateTime.now();
        when(databaseClock.now()).thenReturn(now);

        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(100L)
                .operationNo(OP_NO)
                .bOrderId(B_ORDER_ID)
                .status(SupplierOrderOperationStatus.EXECUTING.getCode())
                .adapterStepCode(AdapterStepCode.PLACE_ORDER.name())
                .executionGeneration(2L)
                .executionLeaseUntil(now.plusSeconds(30))
                .build();
        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);

        String result = runner.runLeaseWatchdog(OP_NO, 2L, "origin-123");

        assertThat(result).isEqualTo("LEASE_NOT_EXPIRED");
        verify(lifecycle, never()).claimForRecovery(any());
    }

    @Test
    @DisplayName("准备看门狗：5分钟超时仍为 PENDING 且无发送 marker 转 MANUAL 保留资金")
    void runPreparationWatchdogTransitionsToManual() {
        when(lifecycle.handlePreparationWatchdog(OP_NO)).thenReturn(true);

        String result = runner.runPreparationWatchdog(OP_NO, "origin-123");

        assertThat(result).isEqualTo("TRANSITIONED_TO_MANUAL");
        verify(lifecycle).handlePreparationWatchdog(OP_NO);
    }
}
