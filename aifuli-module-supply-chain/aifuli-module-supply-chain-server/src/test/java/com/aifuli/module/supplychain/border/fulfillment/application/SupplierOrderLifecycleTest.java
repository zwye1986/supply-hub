package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentExceptionCaseDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAssetMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentCallLogMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentExceptionCaseMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RawStatusObservationMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.CardOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商订单生命周期服务单测：状态推进、资金 Commit/Release 闭环、资产入库与幂等。
 */
@ExtendWith(MockitoExtension.class)
class SupplierOrderLifecycleTest {

    @Mock
    private BOrderMapper bOrderMapper;
    @Mock
    private FulfillmentOperationMapper operationMapper;
    @Mock
    private FulfillmentRequestMapper requestMapper;
    @Mock
    private SupplyChainOrderMapper orderMapper;
    @Mock
    private FulfillmentAssetMapper assetMapper;
    @Mock
    private FulfillmentCallLogMapper callLogMapper;
    @Mock
    private RawStatusObservationMapper observationMapper;
    @Mock
    private FulfillmentExceptionCaseMapper exceptionCaseMapper;
    @Mock
    private OrderStatusLogMapper statusLogMapper;
    @Mock
    private FundReservationService fundReservationService;
    @Mock
    private EnvelopeCryptoPort envelopeCrypto;
    @Mock
    private HmacFingerprintPort hmacFingerprint;
    @Mock
    private BusinessNoGenerator businessNoGenerator;
    @Mock
    private SupplyChainDatabaseClock databaseClock;

    private final CardOrderSummaryPolicy cardOrderSummaryPolicy = new CardOrderSummaryPolicy();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SupplierOrderLifecycle lifecycle;

    private final LocalDateTime fixedNow = LocalDateTime.of(2026, 8, 11, 10, 0, 0);

    @BeforeEach
    void setUp() {
        lifecycle = new SupplierOrderLifecycle(
                bOrderMapper,
                operationMapper,
                requestMapper,
                orderMapper,
                assetMapper,
                callLogMapper,
                observationMapper,
                exceptionCaseMapper,
                statusLogMapper,
                fundReservationService,
                cardOrderSummaryPolicy,
                envelopeCrypto,
                hmacFingerprint,
                businessNoGenerator,
                databaseClock,
                objectMapper
        );
    }

    @Test
    void transitionSucceededClosesStateFundsAndAssets() {
        String operationNo = "OP_001";
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(100L)
                .operationNo(operationNo)
                .bOrderId(200L)
                .status((short) 10)
                .attemptCount(0)
                .build();

        BOrderDO bOrder = BOrderDO.builder()
                .id(200L)
                .bOrderNo("BO_001")
                .fulfillmentRequestId(300L)
                .orderId(400L)
                .supplierId(500L)
                .requestedQuantity(1L)
                .expectedSupplierSettlementAmount(1000L)
                .fulfillmentStatus((short) 10)
                .activeFlag(Boolean.TRUE)
                .build();

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .id(300L)
                .requestNo("FR_001")
                .buyerId(600L)
                .status((short) 10)
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(400L)
                .orderNo("SC_001")
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .buyerId(600L)
                .buyerUserId("user_001")
                .quantity(1L)
                .orderStatus((short) 10)
                .detailVersion(1L)
                .build();

        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(bOrderMapper.selectById(200L)).thenReturn(bOrder);
        when(requestMapper.selectById(300L)).thenReturn(request);
        when(orderMapper.selectById(400L)).thenReturn(order);
        when(databaseClock.now()).thenReturn(fixedNow);
        when(operationMapper.updateById(any(FulfillmentOperationDO.class))).thenReturn(1);
        when(bOrderMapper.updateById(any(BOrderDO.class))).thenReturn(1);
        when(requestMapper.updateById(any(FulfillmentRequestDO.class))).thenReturn(1);
        when(orderMapper.updateById(any(SupplyChainOrderDO.class))).thenReturn(1);

        when(businessNoGenerator.next("FA_")).thenReturn("FA_001");
        when(envelopeCrypto.encrypt(any(), any())).thenReturn(
                new EnvelopeCryptoPort.EncryptedPayload(new byte[]{1, 2, 3}, "1"));
        when(hmacFingerprint.fingerprint(eq("FULFILLMENT_ASSET"), any())).thenReturn("mock-fingerprint".getBytes(StandardCharsets.UTF_8));

        OperationAssetFact assetFact = OperationAssetFact.builder()
                .sequenceNo(1)
                .assetType("CARD_SECRET")
                .cardNo("CARD123456789")
                .cardSecret("SECRET123456")
                .build();

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .externalOrderNo("EXT_SUPPLIER_001")
                .reportedSettlementAmount(950L)
                .assets(List.of(assetFact))
                .build();

        SupplierOrderLifecycle.TransitionResult result = lifecycle.transition(operationNo, outcome, "TRACE_001");

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.operationStatus()).isEqualTo((short) 40);
        assertThat(result.bOrderStatus()).isEqualTo((short) 40);
        assertThat(result.orderStatus()).isEqualTo((short) 30);

        // Verify BOrder activeFlag cleared to null (so unique active constraint allows next or ends)
        assertThat(bOrder.getActiveFlag()).isNull();
        assertThat(bOrder.getFulfilledQuantity()).isEqualTo(1L);
        assertThat(bOrder.getReportedSupplierSettlementAmount()).isEqualTo(950L);

        // Verify Fund Reservation commit was called
        verify(fundReservationService).commitByReference(
                eq(600L),
                eq(FundReferenceType.CARD_ORDER.code()),
                eq("FR_001"),
                eq("SC_001"),
                eq("TRACE_001"),
                eq("BO_001")
        );

        // Verify Asset insertion
        ArgumentCaptor<FulfillmentAssetDO> assetCaptor = ArgumentCaptor.forClass(FulfillmentAssetDO.class);
        verify(assetMapper).insert(assetCaptor.capture());
        FulfillmentAssetDO capturedAsset = assetCaptor.getValue();
        assertThat(capturedAsset.getAssetNo()).isEqualTo("FA_001");
        assertThat(capturedAsset.getBuyerId()).isEqualTo(600L);
        assertThat(capturedAsset.getBuyerUserId()).isEqualTo("user_001");
        assertThat(capturedAsset.getMaskedValue()).isNotBlank();

        // Verify Order status log
        ArgumentCaptor<OrderStatusLogDO> logCaptor = ArgumentCaptor.forClass(OrderStatusLogDO.class);
        verify(statusLogMapper).insert(logCaptor.capture());
        OrderStatusLogDO capturedLog = logCaptor.getValue();
        assertThat(capturedLog.getOrderId()).isEqualTo(400L);
        assertThat(capturedLog.getFromOrderStatus()).isEqualTo((short) 10);
        assertThat(capturedLog.getToOrderStatus()).isEqualTo((short) 30);
        assertThat(capturedLog.getBusinessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_CARD_FULFILLED);
    }

    @Test
    void transitionRejectedReleasesFundsAndAdvancesFailedState() {
        String operationNo = "OP_002";
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(101L)
                .operationNo(operationNo)
                .bOrderId(201L)
                .status((short) 10)
                .attemptCount(0)
                .build();

        BOrderDO bOrder = BOrderDO.builder()
                .id(201L)
                .bOrderNo("BO_002")
                .fulfillmentRequestId(301L)
                .orderId(401L)
                .supplierId(501L)
                .requestedQuantity(1L)
                .fulfillmentStatus((short) 10)
                .activeFlag(Boolean.TRUE)
                .build();

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .id(301L)
                .requestNo("FR_002")
                .buyerId(601L)
                .status((short) 10)
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(401L)
                .orderNo("SC_002")
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .buyerId(601L)
                .buyerUserId("user_002")
                .quantity(1L)
                .orderStatus((short) 10)
                .detailVersion(1L)
                .build();

        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(bOrderMapper.selectById(201L)).thenReturn(bOrder);
        when(requestMapper.selectById(301L)).thenReturn(request);
        when(orderMapper.selectById(401L)).thenReturn(order);
        when(databaseClock.now()).thenReturn(fixedNow);
        when(operationMapper.updateById(any(FulfillmentOperationDO.class))).thenReturn(1);
        when(bOrderMapper.updateById(any(BOrderDO.class))).thenReturn(1);
        when(requestMapper.updateById(any(FulfillmentRequestDO.class))).thenReturn(1);
        when(orderMapper.updateById(any(SupplyChainOrderDO.class))).thenReturn(1);

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.REJECTED)
                .externalErrorCode("INSUFFICIENT_STOCK")
                .externalErrorMessage("Supplier out of stock")
                .build();

        SupplierOrderLifecycle.TransitionResult result = lifecycle.transition(operationNo, outcome, "TRACE_002");

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.operationStatus()).isEqualTo((short) 50);
        assertThat(result.bOrderStatus()).isEqualTo((short) 50);
        assertThat(result.orderStatus()).isEqualTo((short) 40);

        assertThat(bOrder.getActiveFlag()).isNull();

        // Verify Fund Reservation release was called
        verify(fundReservationService).releaseByReference(
                eq(601L),
                eq(FundReferenceType.CARD_ORDER.code()),
                eq("FR_002"),
                eq("SC_002"),
                eq("TRACE_002"),
                eq("BO_002")
        );

        // Verify no asset was inserted
        verify(assetMapper, never()).insert(any(FulfillmentAssetDO.class));

        // Verify Order status log
        ArgumentCaptor<OrderStatusLogDO> logCaptor = ArgumentCaptor.forClass(OrderStatusLogDO.class);
        verify(statusLogMapper).insert(logCaptor.capture());
        OrderStatusLogDO capturedLog = logCaptor.getValue();
        assertThat(capturedLog.getOrderId()).isEqualTo(401L);
        assertThat(capturedLog.getFromOrderStatus()).isEqualTo((short) 10);
        assertThat(capturedLog.getToOrderStatus()).isEqualTo((short) 40);
        assertThat(capturedLog.getBusinessStage()).isEqualTo(SupplyChainOrderTypes.STAGE_CARD_FAILED);
        assertThat(capturedLog.getReasonCode()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void transitionResultUnknownSchedulesRetryAndDoesNotTouchFunds() {
        String operationNo = "OP_003";
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(102L)
                .operationNo(operationNo)
                .bOrderId(202L)
                .status((short) 10)
                .attemptCount(1)
                .build();

        BOrderDO bOrder = BOrderDO.builder()
                .id(202L)
                .bOrderNo("BO_003")
                .fulfillmentRequestId(302L)
                .orderId(402L)
                .fulfillmentStatus((short) 10)
                .activeFlag(Boolean.TRUE)
                .build();

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .id(302L)
                .requestNo("FR_003")
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(402L)
                .orderNo("SC_003")
                .orderStatus((short) 10)
                .build();

        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(bOrderMapper.selectById(202L)).thenReturn(bOrder);
        when(requestMapper.selectById(302L)).thenReturn(request);
        when(orderMapper.selectById(402L)).thenReturn(order);
        when(databaseClock.now()).thenReturn(fixedNow);
        when(operationMapper.updateById(any(FulfillmentOperationDO.class))).thenReturn(1);
        when(bOrderMapper.updateById(any(BOrderDO.class))).thenReturn(1);

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN)
                .externalErrorMessage("Timeout connecting to supplier")
                .build();

        SupplierOrderLifecycle.TransitionResult result = lifecycle.transition(operationNo, outcome, "TRACE_003");

        assertThat(result.isTerminal()).isFalse();
        assertThat(result.operationStatus()).isEqualTo((short) 30);
        assertThat(result.bOrderStatus()).isEqualTo((short) 20);

        // Next recover scheduled at now + 30s
        assertThat(operation.getNextRecoverAt()).isEqualTo(fixedNow.plusSeconds(30));

        // Funds should NOT be touched
        verify(fundReservationService, never()).commitByReference(any(), any(), any(), any(), any(), any());
        verify(fundReservationService, never()).releaseByReference(any(), any(), any(), any(), any(), any());
        verify(statusLogMapper, never()).insert(any(OrderStatusLogDO.class));
    }

    @Test
    void transitionIdempotentWhenAlreadyTerminal() {
        String operationNo = "OP_004";
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(103L)
                .operationNo(operationNo)
                .bOrderId(203L)
                .status((short) 40) // Already succeeded
                .build();

        BOrderDO bOrder = BOrderDO.builder()
                .id(203L)
                .bOrderNo("BO_004")
                .fulfillmentRequestId(303L)
                .orderId(403L)
                .fulfillmentStatus((short) 40)
                .build();

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .id(303L)
                .requestNo("FR_004")
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(403L)
                .orderNo("SC_004")
                .orderStatus((short) 30)
                .build();

        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(bOrderMapper.selectById(203L)).thenReturn(bOrder);
        when(requestMapper.selectById(303L)).thenReturn(request);
        when(orderMapper.selectById(403L)).thenReturn(order);

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .build();

        SupplierOrderLifecycle.TransitionResult result = lifecycle.transition(operationNo, outcome, "TRACE_004");

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.operationStatus()).isEqualTo((short) 40);

        // No database updates performed
        verify(operationMapper, never()).updateById(any(FulfillmentOperationDO.class));
        verify(bOrderMapper, never()).updateById(any(BOrderDO.class));
        verify(fundReservationService, never()).commitByReference(any(), any(), any(), any(), any(), any());
    }

    @Test
    void authoritativeSucceededWithEmptyCardsTransitionsToManual() {
        String operationNo = "OP_005";
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(105L)
                .operationNo(operationNo)
                .bOrderId(205L)
                .operationKind("ISSUE_ASSET")
                .adapterStepCode("SEND_KM")
                .status((short) 10)
                .attemptCount(0)
                .build();

        BOrderDO bOrder = BOrderDO.builder()
                .id(205L)
                .bOrderNo("BO_005")
                .fulfillmentRequestId(305L)
                .orderId(405L)
                .supplierId(505L)
                .requestedQuantity(1L)
                .expectedSupplierSettlementAmount(1000L)
                .fulfillmentStatus((short) 10)
                .activeFlag(Boolean.TRUE)
                .build();

        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .id(305L)
                .requestNo("FR_005")
                .buyerId(600L)
                .status((short) 10)
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(405L)
                .orderNo("SC_005")
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .buyerId(600L)
                .buyerUserId("user_005")
                .quantity(1L)
                .orderStatus((short) 10)
                .detailVersion(1L)
                .build();

        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(bOrderMapper.selectById(205L)).thenReturn(bOrder);
        when(requestMapper.selectById(305L)).thenReturn(request);
        when(orderMapper.selectById(405L)).thenReturn(order);
        when(databaseClock.now()).thenReturn(fixedNow);
        when(operationMapper.updateById(any(FulfillmentOperationDO.class))).thenReturn(1);
        when(bOrderMapper.updateById(any(BOrderDO.class))).thenReturn(1);
        when(requestMapper.updateById(any(FulfillmentRequestDO.class))).thenReturn(1);
        when(orderMapper.updateById(any(SupplyChainOrderDO.class))).thenReturn(1);
        when(exceptionCaseMapper.selectActiveByOperationId(105L)).thenReturn(null);
        when(businessNoGenerator.next("CASE_")).thenReturn("CASE_005");
        when(hmacFingerprint.fingerprint(eq("EXCEPTION_CASE"), any()))
                .thenReturn("case-digest".getBytes(StandardCharsets.UTF_8));

        // 权威成功，但是没有卡密返回 (assets 为空)
        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .externalOrderNo("QNM_SN_005")
                .reportedSettlementAmount(1000L)
                .assets(List.of())
                .build();

        SupplierOrderLifecycle.TransitionResult result = lifecycle.transition(operationNo, outcome, "TRACE_005");

        // 供应商订单层面标记权威成功，终态
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.operationStatus()).isEqualTo((short) 40);
        assertThat(result.bOrderStatus()).isEqualTo((short) 40);
        // 总订单转入人工介入状态 (90 MANUAL)
        assertThat(result.orderStatus()).isEqualTo((short) 90);

        // BOrder 成功，实扣供应商资金
        assertThat(bOrder.getFulfillmentStatus()).isEqualTo((short) 40);
        assertThat(bOrder.getConfirmedSupplierSettlementAmount()).isEqualTo(1000L);

        // FulfillmentRequest 与总订单进入 MANUAL 90
        assertThat(request.getStatus()).isEqualTo((short) 90);
        assertThat(order.getOrderStatus()).isEqualTo((short) 90);
        assertThat(order.getDetailVersion()).isEqualTo(2L);

        // 资金执行 commit
        verify(fundReservationService).commitByReference(eq(600L),
                eq(FundReferenceType.CARD_ORDER.code()), eq("FR_005"),
                eq("SC_005"), eq("TRACE_005"), eq("BO_005"));

        // 未生成任何履约资产
        verify(assetMapper, never()).insert(any(FulfillmentAssetDO.class));

        // 记录状态变更日志，原因码为 QNM-ASSET-RECOVERY-001
        ArgumentCaptor<OrderStatusLogDO> logCaptor = ArgumentCaptor.forClass(OrderStatusLogDO.class);
        verify(statusLogMapper).insert(logCaptor.capture());
        OrderStatusLogDO log = logCaptor.getValue();
        assertThat(log.getToOrderStatus()).isEqualTo((short) 90);
        assertThat(log.getReasonCode()).isEqualTo("QNM-ASSET-RECOVERY-001");
        assertThat(log.getDetailVersion()).isEqualTo(2L);

        ArgumentCaptor<FulfillmentExceptionCaseDO> caseCaptor = ArgumentCaptor.forClass(FulfillmentExceptionCaseDO.class);
        verify(exceptionCaseMapper).insert(caseCaptor.capture());
        assertThat(caseCaptor.getValue().getReasonCode()).isEqualTo("QNM_ASSET_MISSING_AFTER_SUCCESS");
        assertThat(caseCaptor.getValue().getStatus()).isEqualTo((short) 0);
        assertThat(caseCaptor.getValue().getActiveCaseFlag()).isEqualTo(1);
    }

    @Test
    void claimForExecutionUpdatesPendingToExecuting() {
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .operationNo("OP_CLAIM")
                .status((short) 0)
                .version(1L)
                .build();
        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(databaseClock.now()).thenReturn(fixedNow);
        when(operationMapper.updateById(any(FulfillmentOperationDO.class))).thenReturn(1);

        assertThat(lifecycle.claimForExecution("OP_CLAIM")).isTrue();
        assertThat(operation.getStatus()).isEqualTo((short) 20);
    }

    @Test
    void claimForExecutionRejectsExecutingStatus() {
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .operationNo("OP_BUSY")
                .status((short) 20)
                .build();
        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);

        assertThat(lifecycle.claimForExecution("OP_BUSY")).isFalse();
        verify(operationMapper, never()).updateById(any(FulfillmentOperationDO.class));
    }

    @Test
    void manualRequiredOutcomeUsesOptimisticLock() {
        String operationNo = "OP_MANUAL";
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(106L)
                .operationNo(operationNo)
                .bOrderId(206L)
                .status((short) 20)
                .attemptCount(1)
                .build();
        BOrderDO bOrder = BOrderDO.builder()
                .id(206L)
                .bOrderNo("BO_MANUAL")
                .fulfillmentRequestId(306L)
                .orderId(406L)
                .fulfillmentStatus((short) 20)
                .activeFlag(Boolean.TRUE)
                .build();
        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .id(306L)
                .requestNo("FR_MANUAL")
                .status((short) 20)
                .build();
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(406L)
                .orderNo("SC_MANUAL")
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .quantity(1L)
                .orderStatus((short) 10)
                .detailVersion(1L)
                .build();

        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(bOrderMapper.selectById(206L)).thenReturn(bOrder);
        when(requestMapper.selectById(306L)).thenReturn(request);
        when(orderMapper.selectById(406L)).thenReturn(order);
        when(databaseClock.now()).thenReturn(fixedNow);
        when(operationMapper.updateById(any(FulfillmentOperationDO.class))).thenReturn(1);
        when(bOrderMapper.updateById(any(BOrderDO.class))).thenReturn(1);
        when(requestMapper.updateById(any(FulfillmentRequestDO.class))).thenReturn(1);
        when(orderMapper.updateById(any(SupplyChainOrderDO.class))).thenReturn(1);

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.MANUAL_REQUIRED)
                .externalErrorCode("HTTP_401")
                .build();

        SupplierOrderLifecycle.TransitionResult result = lifecycle.transition(operationNo, outcome, "TRACE_MANUAL");

        assertThat(result.isTerminal()).isTrue();
        assertThat(result.operationStatus()).isEqualTo((short) 90);
        assertThat(result.bOrderStatus()).isEqualTo((short) 90);
        assertThat(result.orderStatus()).isEqualTo((short) 90);
        assertThat(bOrder.getActiveFlag()).isNull();
        verify(operationMapper).updateById(operation);
        verify(orderMapper).updateById(order);
    }

    @Test
    void manualRequiredOutcomeFailsClosedWhenOperationCasLost() {
        FulfillmentOperationDO operation = FulfillmentOperationDO.builder()
                .id(107L)
                .operationNo("OP_CAS")
                .bOrderId(207L)
                .status((short) 20)
                .attemptCount(0)
                .build();
        BOrderDO bOrder = BOrderDO.builder()
                .id(207L)
                .bOrderNo("BO_CAS")
                .fulfillmentRequestId(307L)
                .orderId(407L)
                .build();
        FulfillmentRequestDO request = FulfillmentRequestDO.builder()
                .id(307L)
                .requestNo("FR_CAS")
                .build();
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(407L)
                .orderNo("SC_CAS")
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .quantity(1L)
                .orderStatus((short) 10)
                .detailVersion(1L)
                .build();

        when(operationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(operation);
        when(bOrderMapper.selectById(207L)).thenReturn(bOrder);
        when(requestMapper.selectById(307L)).thenReturn(request);
        when(orderMapper.selectById(407L)).thenReturn(order);
        when(databaseClock.now()).thenReturn(fixedNow);
        when(operationMapper.updateById(any(FulfillmentOperationDO.class))).thenReturn(0);

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(OperationOutcomeClass.MANUAL_REQUIRED)
                .build();

        assertThatThrownBy(() -> lifecycle.transition("OP_CAS", outcome, "TRACE_CAS"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAS");
        verify(bOrderMapper, never()).updateById(any(BOrderDO.class));
    }
}
