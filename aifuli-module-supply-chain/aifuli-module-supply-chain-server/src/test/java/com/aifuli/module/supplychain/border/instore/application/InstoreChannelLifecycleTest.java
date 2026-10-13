package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelUserBindingDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreOperationDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreSupplierOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelUserBindingMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreOperationMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreSupplierOrderMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreIdempotencyKeys;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOperationStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOperationType;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundType;
import com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.InstoreOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountReturnService;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierChannelBindingDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierChannelBindingMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到店购 Lifecycle 纯逻辑单测：1:1 建单、确认/取消幂等、只读查询、退款超额拒绝。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreChannelLifecycleTest {

    private static final String CHANNEL = "RT_MART";
    private static final String TRADE_ID = "TRADE001";
    private static final String ORDER_ID = "ORD001";
    private static final String TRACE = "trace-1";

    @Mock private InstoreChannelUserBindingMapper bindingMapper;
    @Mock private InstoreChannelOrderMapper instoreOrderMapper;
    @Mock private InstorePaymentFlowMapper paymentFlowMapper;
    @Mock private InstoreOperationMapper operationMapper;
    @Mock private InstoreRefundCaseMapper refundCaseMapper;
    @Mock private InstoreSupplierOrderMapper supplierOrderMapper;
    @Mock private SupplyChainOrderMapper orderMapper;
    @Mock private OrderStatusLogMapper statusLogMapper;
    @Mock private SupplierChannelBindingMapper supplierChannelBindingMapper;
    @Mock private SupplierMapper supplierMapper;
    @Mock private FundReservationService fundReservationService;
    @Mock private OutboxEventRepository outboxRepository;
    @Mock private BusinessNoGenerator businessNoGenerator;
    @Mock private InstoreRefundSequencer refundSequencer;
    @Mock private BuyerAccountReturnService accountReturnService;
    @Mock private SupplyChainDatabaseClock databaseClock;

    private InstoreChannelLifecycle lifecycle;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, InstorePaymentFlowDO.class);
        TableInfoHelper.initTableInfo(assistant, InstoreChannelOrderDO.class);
        TableInfoHelper.initTableInfo(assistant, InstoreRefundCaseDO.class);
        TableInfoHelper.initTableInfo(assistant, InstoreOperationDO.class);
        TableInfoHelper.initTableInfo(assistant, SupplyChainOrderDO.class);
    }

    @BeforeEach
    void setUp() {
        lenient().when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
        lifecycle = new InstoreChannelLifecycle(
                databaseClock, bindingMapper, instoreOrderMapper, paymentFlowMapper, operationMapper,
                refundCaseMapper, supplierOrderMapper, orderMapper, statusLogMapper,
                supplierChannelBindingMapper, supplierMapper, fundReservationService,
                outboxRepository, businessNoGenerator,
                new OrderSummaryPolicyRegistry(List.of(new InstoreOrderSummaryPolicy())),
                refundSequencer, accountReturnService, new InstoreOrderSummaryPolicy());
    }

    @Test
    void createPaymentOrderReplaysSameTradeWithoutNewFreeze() {
        InstorePaymentFlowDO existing = paymentFlow(InstorePaymentFlowStatus.PENDING);
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID)).thenReturn(existing);
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.CREATED));
        when(operationMapper.selectByIdempotencyKey(InstoreIdempotencyKeys.preorder(CHANNEL, TRADE_ID)))
                .thenReturn(preorderOperation());

        InstoreChannelLifecycle.PreparedPayment result = lifecycle.createPaymentOrder(createCommand(10_000L, 10_000L), com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers());

        assertThat(result.idempotentReplay()).isTrue();
        assertThat(result.paymentFlowNo()).isEqualTo("IPF_1");
        assertThat(result.invocationId()).isEqualTo("IOP_1");
        verify(fundReservationService, never()).freeze(any());
        verify(paymentFlowMapper, never()).insert(any(InstorePaymentFlowDO.class));
    }

    @Test
    void createPaymentOrderRejectsReplayWhenPayAmountDiffers() {
        InstorePaymentFlowDO existing = paymentFlow(InstorePaymentFlowStatus.PENDING);
        existing.setPayAmount(9_000L);
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID)).thenReturn(existing);
        assertThatThrownBy(() -> lifecycle.createPaymentOrder(createCommand(10_000L, 10_000L), com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT.getCode());
    }

    @Test
    void createPaymentOrderRejectsMissingBinding() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID)).thenReturn(null);
        when(bindingMapper.selectByProjectUser(101L, "buyer-user-1", CHANNEL)).thenReturn(null);
        assertThatThrownBy(() -> lifecycle.createPaymentOrder(createCommand(10_000L, 10_000L), com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());
    }

    @Test
    void createPaymentOrderRejectsBindingIdentityMismatch() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID)).thenReturn(null);
        InstoreChannelUserBindingDO binding = activeBinding();
        binding.setBuyerId(9999L);
        when(bindingMapper.selectByProjectUser(101L, "buyer-user-1", CHANNEL)).thenReturn(binding);
        assertThatThrownBy(() -> lifecycle.createPaymentOrder(createCommand(10_000L, 10_000L), com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
    }

    @Test
    void createPaymentOrderFreezesByPaymentFlowAndCreatesPreorder() {
        stubNewHeadCreate();
        ArgumentCaptor<FundReservationService.FreezeCommand> freezeCaptor =
                ArgumentCaptor.forClass(FundReservationService.FreezeCommand.class);
        ArgumentCaptor<InstoreOperationDO> operationCaptor = ArgumentCaptor.forClass(InstoreOperationDO.class);

        InstoreChannelLifecycle.PreparedPayment result = lifecycle.createPaymentOrder(createCommand(10_000L, 10_000L), com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers());

        assertThat(result.idempotentReplay()).isFalse();
        assertThat(result.payAmount()).isEqualTo(10_000L);
        assertThat(result.reservationNo()).isEqualTo("FR_1");
        verify(fundReservationService).freeze(freezeCaptor.capture());
        assertThat(freezeCaptor.getValue().referenceType()).isEqualTo(FundReferenceType.INSTORE_ORDER.code());
        assertThat(freezeCaptor.getValue().referenceNo()).isEqualTo("IPF_FLOW");
        assertThat(freezeCaptor.getValue().amount().cents()).isEqualTo(10_000L);
        verify(operationMapper).insert(operationCaptor.capture());
        assertThat(operationCaptor.getValue().getOperationType()).isEqualTo(InstoreOperationType.PREORDER.name());
        assertThat(operationCaptor.getValue().getIdempotencyKey())
                .isEqualTo(InstoreIdempotencyKeys.preorder(CHANNEL, TRADE_ID));
        assertThat(operationCaptor.getValue().getStatus()).isEqualTo(InstoreOperationStatus.PENDING.getCode());
    }

    @Test
    void createPaymentOrderPersistsTenantScopeWithoutLegacyAnonymousSnapshot() {
        stubNewHeadCreate();
        ArgumentCaptor<InstoreChannelOrderDO> captor =
                ArgumentCaptor.forClass(InstoreChannelOrderDO.class);

        lifecycle.createPaymentOrder(createCommand(10_000L, 10_000L), com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers());

        verify(instoreOrderMapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceSystem()).isEqualTo("AIFULI_MALL");
        assertThat(captor.getValue().getExternalTenantId()).isEqualTo("tenant-a");
        assertThat(captor.getValue().getProjectId()).isEqualTo(101L);
        assertThat(captor.getValue().getCashierProfileVersionId()).isEqualTo(202L);
        assertThat(captor.getValue().getBuyerUserId()).isEqualTo("buyer-user-1");
        assertThat(captor.getValue().getChannelUserId()).isNull();
    }

    @Test
    void createPaymentOrderAdvancesDetailVersionToMatchAcceptedStatusLog() {
        stubNewHeadCreate();
        ArgumentCaptor<OrderStatusLogDO> logCaptor = ArgumentCaptor.forClass(OrderStatusLogDO.class);

        lifecycle.createPaymentOrder(createCommand(10_000L, 10_000L), com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers());

        verify(orderMapper).advanceDetailVersion(eq(1L), eq(0L), eq(1L), any());
        verify(statusLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getDetailVersion()).isEqualTo(1L);
    }

    @Test
    void paymentPreparationContractCarriesFrozenProjectCashierRoute() {
        Map<String, Class<?>> commandComponents = Arrays.stream(
                        InstoreChannelLifecycle.CreatePaymentOrderCommand.class.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType));
        Map<String, Class<?>> resultComponents = Arrays.stream(
                        InstoreChannelLifecycle.PreparedPayment.class.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType));

        assertThat(commandComponents)
                .containsEntry("projectId", Long.class)
                .containsEntry("cashierProfileVersionId", Long.class);
        assertThat(resultComponents)
                .containsEntry("projectId", Long.class)
                .containsEntry("cashierProfileVersionId", Long.class)
                .containsEntry("buyerUserId", String.class)
                .containsEntry("subject", String.class)
                .containsEntry("cashierUrl", String.class);
    }

    @Test
    void createPaymentOrderMapsMissingAccountToBuyerAccountNotOpened() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID)).thenReturn(null);
        when(bindingMapper.selectByProjectUser(101L, "buyer-user-1", CHANNEL)).thenReturn(activeBinding());
        when(supplierChannelBindingMapper.selectCurrentEnabled(CHANNEL, "MERCHANT1")).thenReturn(channelBinding());
        when(supplierMapper.selectById(31L)).thenReturn(supplier());
        when(instoreOrderMapper.selectByChannelOrder(CHANNEL, ORDER_ID)).thenReturn(instoreOrder(InstoreOrderStatus.CREATED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder());
        when(paymentFlowMapper.countByInstoreOrder(2L)).thenReturn(0L);
        when(businessNoGenerator.next(BusinessNoPrefixes.IPF)).thenReturn("IPF_FLOW", "IPF_OTN");
        doAnswer(setId(3L)).when(paymentFlowMapper).insert(any(InstorePaymentFlowDO.class));
        when(fundReservationService.freeze(any()))
                .thenThrow(new IllegalStateException("Buyer CNY account does not exist"));

        assertThatThrownBy(() -> lifecycle.createPaymentOrder(createCommand(10_000L, 10_000L), com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_ACCOUNT_NOT_OPENED.getCode());
    }

    @Test
    void createPaymentOrderRejectsReusedHeadWhenTotalAmountDiffers() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID)).thenReturn(null);
        when(bindingMapper.selectByProjectUser(101L, "buyer-user-1", CHANNEL)).thenReturn(activeBinding());
        when(supplierChannelBindingMapper.selectCurrentEnabled(CHANNEL, "MERCHANT1")).thenReturn(channelBinding());
        when(supplierMapper.selectById(31L)).thenReturn(supplier());
        InstoreChannelOrderDO existingHead = instoreOrder(InstoreOrderStatus.CREATED);
        existingHead.setTotalAmount(8_000L);
        when(instoreOrderMapper.selectByChannelOrder(CHANNEL, ORDER_ID)).thenReturn(existingHead);

        assertThatThrownBy(() -> lifecycle.createPaymentOrder(createCommand(10_000L, 10_000L), com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT.getCode());
    }

    @Test
    void createPaymentOrderRejectsSecondTradeOnSameHead() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, "TRADE002")).thenReturn(null);
        when(bindingMapper.selectByProjectUser(101L, "buyer-user-1", CHANNEL)).thenReturn(activeBinding());
        when(supplierChannelBindingMapper.selectCurrentEnabled(CHANNEL, "MERCHANT1")).thenReturn(channelBinding());
        when(supplierMapper.selectById(31L)).thenReturn(supplier());
        when(instoreOrderMapper.selectByChannelOrder(CHANNEL, ORDER_ID)).thenReturn(instoreOrder(InstoreOrderStatus.CREATED));
        when(paymentFlowMapper.countByInstoreOrder(2L)).thenReturn(1L);

        InstoreChannelLifecycle.CreatePaymentOrderCommand secondTrade =
                new InstoreChannelLifecycle.CreatePaymentOrderCommand(
                        CHANNEL, ORDER_ID, "TRADE002", 1001L, 101L, 202L,
                        "AIFULI_MALL", "tenant-a", "buyer-user-1", "buyer-user-1",
                        10_000L, 1, 10_000L, "COMPANY", "MERCHANT1",
                        "SHOP1", "Shop", "subject", TRACE);
        assertThatThrownBy(() -> lifecycle.createPaymentOrder(secondTrade, com.aifuli.module.supplychain.testsupport.OrderNoTestConfiguration.numbers()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT.getCode());
        verify(fundReservationService, never()).freeze(any());
    }

    @Test
    void claimPaymentPreparationDoesNotAdvanceFlowWhenCasLoses() {
        when(operationMapper.selectByOperationNo("IOP_1")).thenReturn(preorderOperation());
        when(operationMapper.claimPreorder(eq(40L), eq(0L), any())).thenReturn(0);
        InstoreChannelLifecycle.ClaimResult result = lifecycle.claimPaymentPreparation("IOP_1", 0L);
        assertThat(result.claimed()).isFalse();
        verify(paymentFlowMapper, never()).advanceToCashierCreating(anyLong(), anyLong(), any(Short.class), any(), any());
    }

    @Test
    void claimPaymentPreparationAdvancesFlowWhenCasWins() {
        when(operationMapper.selectByOperationNo("IOP_1")).thenReturn(preorderOperation());
        when(operationMapper.claimPreorder(eq(40L), eq(0L), any())).thenReturn(1);
        when(paymentFlowMapper.selectById(3L)).thenReturn(paymentFlow(InstorePaymentFlowStatus.PENDING));
        when(paymentFlowMapper.advanceToCashierCreating(eq(3L), eq(0L),
                eq(InstorePaymentFlowStatus.PROCESSING.getCode()), isNull(), any())).thenReturn(1);
        InstoreChannelLifecycle.ClaimResult result = lifecycle.claimPaymentPreparation("IOP_1", 0L);
        assertThat(result.claimed()).isTrue();
        assertThat(result.operationVersion()).isEqualTo(1L);
        verify(paymentFlowMapper).advanceToCashierCreating(eq(3L), eq(0L),
                eq(InstorePaymentFlowStatus.PROCESSING.getCode()), isNull(), any());
    }

    @Test
    void claimPaymentPreparationFailsWhenPaymentFlowCasLoses() {
        when(operationMapper.selectByOperationNo("IOP_1")).thenReturn(preorderOperation());
        when(operationMapper.claimPreorder(eq(40L), eq(0L), any())).thenReturn(1);
        when(paymentFlowMapper.selectById(3L)).thenReturn(paymentFlow(InstorePaymentFlowStatus.PENDING));
        when(paymentFlowMapper.advanceToCashierCreating(eq(3L), eq(0L),
                eq(InstorePaymentFlowStatus.PROCESSING.getCode()), isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> lifecycle.claimPaymentPreparation("IOP_1", 0L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.ORDER_STATE_CONFLICT.getCode());
    }

    @Test
    void recordCashierOrderFailsWhenOperationCasLoses() {
        when(operationMapper.selectByOperationNo("IOP_1")).thenReturn(preorderOperation());
        when(operationMapper.recordResult(eq(40L), eq(1L), eq(InstoreOperationStatus.RUNNING.getCode()),
                eq("CASHIER_1"), eq("CREATED"), any())).thenReturn(0);
        BuyerCashierPort.CreateCashierOrderResult created = new BuyerCashierPort.CreateCashierOrderResult(
                BuyerCashierPort.CashierOrderStatus.CREATED, "CASHIER_1", "launch", 10_000L, null,
                OffsetDateTime.parse("2026-08-19T01:00:00Z"));

        assertThatThrownBy(() -> lifecycle.recordCashierOrder("IOP_1", 1L, created))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.ORDER_STATE_CONFLICT.getCode());
    }

    @Test
    void recordCashierOrderWritesReferenceOnlyWhenCreated() {
        when(operationMapper.selectByOperationNo("IOP_1")).thenReturn(preorderOperation());
        InstorePaymentFlowDO flow = paymentFlow(InstorePaymentFlowStatus.PROCESSING);
        flow.setVersion(1L);
        when(paymentFlowMapper.selectById(3L)).thenReturn(flow);
        when(operationMapper.recordResult(eq(40L), eq(1L), eq(InstoreOperationStatus.RUNNING.getCode()),
                eq("CASHIER_1"), eq("CREATED"), any())).thenReturn(1);
        when(paymentFlowMapper.recordCashierOrder(eq(3L), eq(1L),
                eq(InstorePaymentFlowStatus.PROCESSING.getCode()), eq("launch"), eq("CASHIER_1"), any()))
                .thenReturn(1);
        BuyerCashierPort.CreateCashierOrderResult created = new BuyerCashierPort.CreateCashierOrderResult(
                BuyerCashierPort.CashierOrderStatus.CREATED, "CASHIER_1", "launch", 10_000L, null,
                OffsetDateTime.parse("2026-08-19T01:00:00Z"));
        lifecycle.recordCashierOrder("IOP_1", 1L, created);
        verify(operationMapper).recordResult(eq(40L), eq(1L), eq(InstoreOperationStatus.RUNNING.getCode()),
                eq("CASHIER_1"), eq("CREATED"), any());
        verify(paymentFlowMapper).recordCashierOrder(eq(3L), eq(1L),
                eq(InstorePaymentFlowStatus.PROCESSING.getCode()), eq("launch"), eq("CASHIER_1"), any());
    }

    @Test
    void recordCashierOrderKeepsOutTradeNoWhenRejected() {
        when(operationMapper.selectByOperationNo("IOP_1")).thenReturn(preorderOperation());
        when(operationMapper.recordResult(eq(40L), eq(1L), eq(InstoreOperationStatus.FAILED.getCode()),
                isNull(), eq("REJECTED"), any())).thenReturn(1);
        BuyerCashierPort.CreateCashierOrderResult rejected = new BuyerCashierPort.CreateCashierOrderResult(
                BuyerCashierPort.CashierOrderStatus.REJECTED, null, null, null, "REJECTED",
                OffsetDateTime.parse("2026-08-19T01:00:00Z"));
        lifecycle.recordCashierOrder("IOP_1", 1L, rejected);
        verify(operationMapper).recordResult(eq(40L), eq(1L), eq(InstoreOperationStatus.FAILED.getCode()),
                isNull(), eq("REJECTED"), any());
        verify(paymentFlowMapper, never()).recordCashierOrder(anyLong(), anyLong(), any(Short.class), any(), any(), any());
    }

    @Test
    void confirmBuyerPaymentRejectsPaidAmountMismatch() {
        InstorePaymentFlowDO flow = paymentFlow(InstorePaymentFlowStatus.PROCESSING);
        when(paymentFlowMapper.selectByOutTradeNo("OTN_1")).thenReturn(flow);
        assertThatThrownBy(() -> lifecycle.confirmBuyerPayment("OTN_1", 9_999L, "CASHIER_1", "TXN", TRACE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT.getCode());
        verify(fundReservationService, never()).commit(any(), any(), any(), any());
    }

    @Test
    void confirmBuyerPaymentReplaysWhenAlreadyPaidWithSameAmount() {
        when(paymentFlowMapper.selectByOutTradeNo("OTN_1")).thenReturn(paymentFlow(InstorePaymentFlowStatus.PAID));
        lifecycle.confirmBuyerPayment("OTN_1", 10_000L, "CASHIER_1", "TXN", TRACE);
        verify(fundReservationService, never()).commit(any(), any(), any(), any());
        verify(supplierOrderMapper, never()).insert(any(InstoreSupplierOrderDO.class));
    }

    @Test
    void confirmBuyerPaymentRejectsWhenHeadAlreadyCompleted() {
        when(paymentFlowMapper.selectByOutTradeNo("OTN_1")).thenReturn(paymentFlow(InstorePaymentFlowStatus.PROCESSING));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.COMPLETED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder());
        assertThatThrownBy(() -> lifecycle.confirmBuyerPayment("OTN_1", 10_000L, "CASHIER_1", "TXN", TRACE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.ORDER_STATE_CONFLICT.getCode());
        verify(fundReservationService, never()).commit(any(), any(), any(), any());
    }

    @Test
    void confirmBuyerPaymentCreatesSucceededSupplierOrderAndCompletesHead() {
        when(paymentFlowMapper.selectByOutTradeNo("OTN_1")).thenReturn(paymentFlow(InstorePaymentFlowStatus.PROCESSING));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.CREATED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder(1L));
        when(orderMapper.advanceDetailVersion(eq(1L), eq(1L), eq(2L), any())).thenReturn(1);
        when(businessNoGenerator.next(BusinessNoPrefixes.ISO)).thenReturn("ISO_1");
        doAnswer(setId(50L)).when(supplierOrderMapper).insert(any(InstoreSupplierOrderDO.class));
        ArgumentCaptor<InstoreSupplierOrderDO> supplierCaptor = ArgumentCaptor.forClass(InstoreSupplierOrderDO.class);

        lifecycle.confirmBuyerPayment("OTN_1", 10_000L, "CASHIER_1", "TXN", TRACE);

        verify(fundReservationService).commit("FR_1", "SC_1", TRACE, "IPF_1");
        verify(supplierOrderMapper).insert(supplierCaptor.capture());
        assertThat(supplierCaptor.getValue().getFulfillmentStatus()).isEqualTo((short) 30);
        assertThat(supplierCaptor.getValue().getChannelPayAmount()).isEqualTo(10_000L);
        assertThat(supplierCaptor.getValue().getSupplierSettlementAmount()).isEqualTo(10_000L);
        verify(paymentFlowMapper).confirmPayment(eq(3L), eq(0L), eq(InstorePaymentFlowStatus.PAID.getCode()),
                eq(10_000L), eq("TXN"), any(), any());
        verify(instoreOrderMapper).advanceStatus(eq(2L), eq(0L), eq(InstoreOrderStatus.COMPLETED.getCode()),
                eq(10_000L), any());
    }

    @Test
    void cancelUnpaidFlowReplaysWhenAlreadyCancelled() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID))
                .thenReturn(paymentFlow(InstorePaymentFlowStatus.CANCELLED));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.CANCELLED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder());

        InstoreChannelLifecycle.CancelPaymentFlowResult result = lifecycle.cancelPaymentFlow(
                new InstoreChannelLifecycle.CancelPaymentFlowCommand(101L, CHANNEL, TRADE_ID, TRACE));

        assertThat(result.convertedToRefund()).isFalse();
        verify(fundReservationService, never()).release(any(), any(), any(), any());
        verify(paymentFlowMapper, never()).cancelFlow(anyLong(), anyLong(), any(Short.class), any());
    }

    @Test
    void cancelUnpaidFlowReleasesFundsAndClosesHeadWhenNoPaidFlowsRemain() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID))
                .thenReturn(paymentFlow(InstorePaymentFlowStatus.PENDING));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.CREATED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder(1L));
        when(orderMapper.advanceDetailVersion(eq(1L), eq(1L), eq(2L), any())).thenReturn(1);
        when(paymentFlowMapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<OrderStatusLogDO> logCaptor = ArgumentCaptor.forClass(OrderStatusLogDO.class);

        InstoreChannelLifecycle.CancelPaymentFlowResult result = lifecycle.cancelPaymentFlow(
                new InstoreChannelLifecycle.CancelPaymentFlowCommand(101L, CHANNEL, TRADE_ID, TRACE));

        assertThat(result.convertedToRefund()).isFalse();
        assertThat(result.refundCaseNo()).isNull();
        verify(fundReservationService).release("FR_1", "SC_1", TRACE, "IPF_1");
        verify(paymentFlowMapper).cancelFlow(eq(3L), eq(0L), eq(InstorePaymentFlowStatus.CANCELLED.getCode()), any());
        verify(instoreOrderMapper).advanceStatus(eq(2L), eq(0L), eq(InstoreOrderStatus.CANCELLED.getCode()),
                isNull(), any());
        verify(orderMapper).advanceDetailVersion(eq(1L), eq(1L), eq(2L), any());
        verify(statusLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getDetailVersion()).isEqualTo(2L);
        verify(refundCaseMapper, never()).insert(any(InstoreRefundCaseDO.class));
    }

    @Test
    void cancelUnpaidFlowKeepsHeadOpenWhenOtherPaidFlowsExist() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID))
                .thenReturn(paymentFlow(InstorePaymentFlowStatus.PROCESSING));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.COMPLETED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder());
        when(paymentFlowMapper.selectCount(any())).thenReturn(1L);

        lifecycle.cancelPaymentFlow(new InstoreChannelLifecycle.CancelPaymentFlowCommand(101L, CHANNEL, TRADE_ID, TRACE));

        verify(instoreOrderMapper, never()).advanceStatus(anyLong(), anyLong(), any(Short.class), any(), any());
        verify(refundCaseMapper, never()).insert(any(InstoreRefundCaseDO.class));
    }

    @Test
    void cancelPaidFlowCreatesFullCancelRefundCase() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID))
                .thenReturn(paymentFlow(InstorePaymentFlowStatus.PAID));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.COMPLETED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder());
        when(refundCaseMapper.selectByChannelRefundNo(CHANNEL, InstoreIdempotencyKeys.cancelRefundChannelNo(TRADE_ID)))
                .thenReturn(null);
        when(refundCaseMapper.selectByFlowAndType(3L, InstoreRefundType.CANCEL_REFUND.name())).thenReturn(null);
        when(refundCaseMapper.selectByFlowAndType(3L, InstoreRefundType.CHANNEL_REFUND.name())).thenReturn(null);
        when(refundSequencer.nextSequence(3L)).thenReturn(1);
        when(businessNoGenerator.next(BusinessNoPrefixes.IRC)).thenReturn("IRC_1");
        ArgumentCaptor<InstoreRefundCaseDO> refundCaptor = ArgumentCaptor.forClass(InstoreRefundCaseDO.class);

        InstoreChannelLifecycle.CancelPaymentFlowResult result = lifecycle.cancelPaymentFlow(
                new InstoreChannelLifecycle.CancelPaymentFlowCommand(101L, CHANNEL, TRADE_ID, TRACE));

        assertThat(result.convertedToRefund()).isTrue();
        assertThat(result.refundCaseNo()).isEqualTo("IRC_1");
        verify(refundCaseMapper).insert(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getRefundType()).isEqualTo(InstoreRefundType.CANCEL_REFUND.name());
        assertThat(refundCaptor.getValue().getChannelRefundAmount()).isEqualTo(10_000L);
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(InstoreRefundStatus.QUEUED.getCode());
        assertThat(refundCaptor.getValue().getChannelRefundNo())
                .isEqualTo(InstoreIdempotencyKeys.cancelRefundChannelNo(TRADE_ID));
        verify(fundReservationService, never()).release(any(), any(), any(), any());
        verify(outboxRepository, never()).appendLocal(anyString(), anyString(), anyLong(), anyString(), anyString(),
                anyMap(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void cancelPaidFlowReplaysWhenInsertHitsDuplicateKey() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID))
                .thenReturn(paymentFlow(InstorePaymentFlowStatus.PAID));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.COMPLETED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder());
        when(refundCaseMapper.selectByChannelRefundNo(CHANNEL, InstoreIdempotencyKeys.cancelRefundChannelNo(TRADE_ID)))
                .thenReturn(null)
                .thenReturn(InstoreRefundCaseDO.builder().refundCaseNo("IRC_RACE")
                        .status(InstoreRefundStatus.BUYER_REFUNDING.getCode()).build());
        when(refundCaseMapper.selectByFlowAndType(3L, InstoreRefundType.CANCEL_REFUND.name())).thenReturn(null);
        when(refundCaseMapper.selectByFlowAndType(3L, InstoreRefundType.CHANNEL_REFUND.name())).thenReturn(null);
        when(refundSequencer.nextSequence(3L)).thenReturn(1);
        when(businessNoGenerator.next(BusinessNoPrefixes.IRC)).thenReturn("IRC_LOST");
        doThrow(new org.springframework.dao.DuplicateKeyException("uk_sc_instore_refund_channel_no"))
                .when(refundCaseMapper).insert(any(InstoreRefundCaseDO.class));

        InstoreChannelLifecycle.CancelPaymentFlowResult result = lifecycle.cancelPaymentFlow(
                new InstoreChannelLifecycle.CancelPaymentFlowCommand(101L, CHANNEL, TRADE_ID, TRACE));

        assertThat(result.convertedToRefund()).isTrue();
        assertThat(result.refundCaseNo()).isEqualTo("IRC_RACE");
        verify(outboxRepository, never()).appendLocal(anyString(), anyString(), anyLong(), anyString(), anyString(),
                anyMap(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void cancelPaidFlowReplaysExistingCancelRefundCase() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID))
                .thenReturn(paymentFlow(InstorePaymentFlowStatus.PAID));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.COMPLETED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder());
        when(refundCaseMapper.selectByChannelRefundNo(CHANNEL, InstoreIdempotencyKeys.cancelRefundChannelNo(TRADE_ID)))
                .thenReturn(InstoreRefundCaseDO.builder().refundCaseNo("IRC_EXISTING")
                        .paymentFlowId(3L).status(InstoreRefundStatus.BUYER_REFUNDING.getCode()).build());

        InstoreChannelLifecycle.CancelPaymentFlowResult result = lifecycle.cancelPaymentFlow(
                new InstoreChannelLifecycle.CancelPaymentFlowCommand(101L, CHANNEL, TRADE_ID, TRACE));

        assertThat(result.convertedToRefund()).isTrue();
        assertThat(result.refundCaseNo()).isEqualTo("IRC_EXISTING");
        verify(refundCaseMapper, never()).insert(any(InstoreRefundCaseDO.class));
        verify(refundSequencer, never()).nextSequence(anyLong());
    }

    @Test
    void cancelPaidFlowFailsWhenChannelRefundExists() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID))
                .thenReturn(paymentFlow(InstorePaymentFlowStatus.PAID));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.COMPLETED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder());
        when(refundCaseMapper.selectByChannelRefundNo(CHANNEL, InstoreIdempotencyKeys.cancelRefundChannelNo(TRADE_ID)))
                .thenReturn(null);
        when(refundCaseMapper.selectByFlowAndType(3L, InstoreRefundType.CANCEL_REFUND.name())).thenReturn(null);
        when(refundCaseMapper.selectByFlowAndType(3L, InstoreRefundType.CHANNEL_REFUND.name()))
                .thenReturn(InstoreRefundCaseDO.builder().id(9L).build());
        assertThatThrownBy(() -> lifecycle.cancelPaymentFlow(
                new InstoreChannelLifecycle.CancelPaymentFlowCommand(101L, CHANNEL, TRADE_ID, TRACE)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CANCEL_REFUND_FAILED.getCode());
    }

    @Test
    void queryPaymentStatusReturnsInternalPayStatus() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID))
                .thenReturn(paymentFlow(InstorePaymentFlowStatus.PAID));
        InstoreChannelLifecycle.PaymentStatusView view = lifecycle.queryPaymentStatus(CHANNEL, TRADE_ID);
        assertThat(view.outTradeNo()).isEqualTo("OTN_1");
        assertThat(view.payStatus()).isEqualTo(InstorePaymentFlowStatus.PAID.getCode());
        assertThat(view.payAmount()).isEqualTo(10_000L);
    }

    @Test
    void queryPaymentStatusRejectsMissingFlow() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID)).thenReturn(null);
        assertThatThrownBy(() -> lifecycle.queryPaymentStatus(CHANNEL, TRADE_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_PAYMENT_FLOW_NOT_FOUND.getCode());
    }

    @Test
    void queryRefundStatusReturnsInternalStatus() {
        when(refundCaseMapper.selectByChannelRefundNo(CHANNEL, "RFN001"))
                .thenReturn(InstoreRefundCaseDO.builder()
                        .refundCaseNo("IRC_1").channelRefundNo("RFN001")
                        .refundType(InstoreRefundType.CHANNEL_REFUND.name())
                        .status(InstoreRefundStatus.QUEUED.getCode())
                        .channelRefundAmount(3_000L).build());
        InstoreChannelLifecycle.RefundStatusView view = lifecycle.queryRefundStatus(CHANNEL, "RFN001");
        assertThat(view.refundCaseNo()).isEqualTo("IRC_1");
        assertThat(view.status()).isEqualTo(InstoreRefundStatus.QUEUED.getCode());
        assertThat(view.channelRefundAmount()).isEqualTo(3_000L);
    }

    @Test
    void queryRefundStatusRejectsMissingCase() {
        when(refundCaseMapper.selectByChannelRefundNo(CHANNEL, "RFN001")).thenReturn(null);
        assertThatThrownBy(() -> lifecycle.queryRefundStatus(CHANNEL, "RFN001"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_REFUND_CASE_NOT_FOUND.getCode());
    }

    @Test
    void acceptRefundReplaysExistingChannelRefundNo() {
        when(refundCaseMapper.selectByChannelRefundNo(CHANNEL, "RFN001"))
                .thenReturn(InstoreRefundCaseDO.builder().refundCaseNo("IRC_EXISTING")
                        .instoreOrderId(2L).paymentFlowId(3L)
                        .requestFingerprint(refundFingerprint(TRADE_ID,
                                InstoreRefundType.CHANNEL_REFUND.name(), 1_000L))
                        .status(InstoreRefundStatus.BUYER_REFUNDING.getCode()).build());
        when(instoreOrderMapper.selectByIdForUpdate(2L)).thenReturn(instoreOrder(InstoreOrderStatus.COMPLETED));
        InstoreChannelLifecycle.AcceptRefundOutcome outcome = lifecycle.acceptRefund(
                new InstoreChannelLifecycle.AcceptRefundCommand(
                        101L, CHANNEL, "RFN001", TRADE_ID, InstoreRefundType.CHANNEL_REFUND.name(), 1_000L, TRACE));
        assertThat(outcome.refundCaseNo()).isEqualTo("IRC_EXISTING");
        assertThat(outcome.claimedForDispatch()).isFalse();
        verify(refundCaseMapper, never()).insert(any(InstoreRefundCaseDO.class));
    }

    @Test
    void acceptRefundRejectsZeroAmountBeforeIdempotentReplay() {
        assertThatThrownBy(() -> lifecycle.acceptRefund(new InstoreChannelLifecycle.AcceptRefundCommand(
                101L, CHANNEL, "RFN001", TRADE_ID, InstoreRefundType.CHANNEL_REFUND.name(), 0L, TRACE)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_INVALID.getCode());

        verify(refundCaseMapper, never()).selectByChannelRefundNo(anyString(), anyString());
        verify(refundCaseMapper, never()).insert(any(InstoreRefundCaseDO.class));
    }

    @Test
    void acceptRefundRejectsNegativeAmountBeforeIdempotentReplay() {
        assertThatThrownBy(() -> lifecycle.acceptRefund(new InstoreChannelLifecycle.AcceptRefundCommand(
                101L, CHANNEL, "RFN001", TRADE_ID, InstoreRefundType.CHANNEL_REFUND.name(), -1L, TRACE)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_INVALID.getCode());

        verify(refundCaseMapper, never()).selectByChannelRefundNo(anyString(), anyString());
        verify(refundCaseMapper, never()).insert(any(InstoreRefundCaseDO.class));
    }

    @Test
    void acceptRefundRejectsChangedRequestDuringIdempotentReplay() {
        when(refundCaseMapper.selectByChannelRefundNo(CHANNEL, "RFN001"))
                .thenReturn(InstoreRefundCaseDO.builder().refundCaseNo("IRC_EXISTING")
                        .instoreOrderId(2L).paymentFlowId(3L)
                        .requestFingerprint(refundFingerprint(TRADE_ID,
                                InstoreRefundType.CHANNEL_REFUND.name(), 1_000L))
                        .status(InstoreRefundStatus.QUEUED.getCode()).build());

        assertThatThrownBy(() -> lifecycle.acceptRefund(new InstoreChannelLifecycle.AcceptRefundCommand(
                101L, CHANNEL, "RFN001", TRADE_ID,
                InstoreRefundType.CHANNEL_REFUND.name(), 999L, TRACE)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_IDENTITY_CONFLICT.getCode());

        verify(instoreOrderMapper, never()).selectByIdForUpdate(anyLong());
        verify(operationMapper, never()).insert(any(InstoreOperationDO.class));
    }

    @Test
    void acceptRefundRejectsAmountExceedingRefundable() {
        when(refundCaseMapper.selectByChannelRefundNo(CHANNEL, "RFN001")).thenReturn(null);
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID))
                .thenReturn(paymentFlow(InstorePaymentFlowStatus.PAID));
        InstoreChannelOrderDO head = instoreOrder(InstoreOrderStatus.COMPLETED);
        head.setTotalPaidAmount(10_000L);
        head.setTotalChannelRefundAmount(9_500L);
        when(instoreOrderMapper.selectByIdForUpdate(2L)).thenReturn(head);
        assertThatThrownBy(() -> lifecycle.acceptRefund(new InstoreChannelLifecycle.AcceptRefundCommand(
                101L, CHANNEL, "RFN001", TRADE_ID, InstoreRefundType.CHANNEL_REFUND.name(), 600L, TRACE)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_EXCEEDED.getCode());
    }

    @Test
    void acceptRefundCreatesQueuedCaseWithNextSequence() {
        when(refundCaseMapper.selectByChannelRefundNo(CHANNEL, "RFN001")).thenReturn(null);
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID))
                .thenReturn(paymentFlow(InstorePaymentFlowStatus.PAID));
        InstoreChannelOrderDO head = instoreOrder(InstoreOrderStatus.COMPLETED);
        head.setTotalPaidAmount(10_000L);
        head.setTotalChannelRefundAmount(0L);
        when(instoreOrderMapper.selectByIdForUpdate(2L)).thenReturn(head);
        when(refundSequencer.nextSequence(3L)).thenReturn(2);
        when(businessNoGenerator.next(BusinessNoPrefixes.IRC)).thenReturn("IRC_2");
        ArgumentCaptor<InstoreRefundCaseDO> refundCaptor = ArgumentCaptor.forClass(InstoreRefundCaseDO.class);

        InstoreChannelLifecycle.AcceptRefundOutcome outcome = lifecycle.acceptRefund(
                new InstoreChannelLifecycle.AcceptRefundCommand(
                        101L, CHANNEL, "RFN001", TRADE_ID, InstoreRefundType.CHANNEL_REFUND.name(), 3_000L, TRACE));

        assertThat(outcome.refundCaseNo()).isEqualTo("IRC_2");
        verify(refundCaseMapper).insert(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getRefundSequence()).isEqualTo(2);
        assertThat(refundCaptor.getValue().getRefundType()).isEqualTo(InstoreRefundType.CHANNEL_REFUND.name());
        assertThat(refundCaptor.getValue().getChannelRefundAmount()).isEqualTo(3_000L);
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(InstoreRefundStatus.QUEUED.getCode());
    }

    @Test
    void applyBuyerRefundResultAccumulatesAmountsOnSuccess() {
        InstoreRefundCaseDO refundCase = InstoreRefundCaseDO.builder()
                .id(9L).refundCaseNo("IRC_1").paymentFlowId(3L).instoreOrderId(2L).orderId(1L)
                .channelCode(CHANNEL).channelRefundNo("RFN001").channelRefundAmount(3_000L)
                .status(InstoreRefundStatus.BUYER_REFUNDING.getCode()).buyerRefundingFlag((byte) 1)
                .version(0L).build();
        when(refundCaseMapper.selectByRefundCaseNo("IRC_1")).thenReturn(refundCase);
        when(paymentFlowMapper.selectById(3L)).thenReturn(paymentFlow(InstorePaymentFlowStatus.PAID));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.COMPLETED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder());
        when(refundCaseMapper.completeRefund(eq(9L), eq(0L), eq(InstoreRefundStatus.SUCCESS.getCode()),
                eq(3_000L), eq(3_000L), eq("CASHIER_RF"), isNull(), any())).thenReturn(1);
        when(instoreOrderMapper.accumulateRefundAmounts(eq(2L), eq(0L), eq(3_000L), eq(3_000L),
                eq(3_000L), any())).thenReturn(1);
        when(refundCaseMapper.selectOpenByFlow(3L)).thenReturn(List.of());
        when(orderMapper.advanceAftersaleStatus(eq(1L), eq(0L), eq(1L), anyShort(), any())).thenReturn(1);
        when(refundCaseMapper.selectNextQueuedByFlow(3L)).thenReturn(null);

        InstoreChannelLifecycle.RefundSettlementOutcome outcome = lifecycle.applyBuyerRefundResult(
                "IRC_1", InstoreRefundStatus.SUCCESS.name(), 3_000L, "CASHIER_RF", null, TRACE);

        assertThat(outcome.finalStatus()).isEqualTo(InstoreRefundStatus.SUCCESS.getCode());
        assertThat(outcome.nextClaimed()).isFalse();
        verify(instoreOrderMapper).accumulateRefundAmounts(eq(2L), eq(0L), eq(3_000L), eq(3_000L),
                eq(3_000L), any());
        verify(refundCaseMapper).completeRefund(eq(9L), eq(0L), eq(InstoreRefundStatus.SUCCESS.getCode()),
                eq(3_000L), eq(3_000L), eq("CASHIER_RF"), isNull(), any());
        verify(outboxRepository).appendLocal(anyString(), eq("IRC_1"), eq(1L), anyString(), anyString(),
                anyMap(), eq(TRACE), eq("SC_1"), eq("IRC_1"), any());
    }

    @Test
    void applyBuyerRefundResultRejectsUnknownStatus() {
        InstoreRefundCaseDO refundCase = InstoreRefundCaseDO.builder()
                .id(9L).refundCaseNo("IRC_1").paymentFlowId(3L).instoreOrderId(2L).orderId(1L)
                .channelCode(CHANNEL).channelRefundNo("RFN001").channelRefundAmount(3_000L)
                .version(0L).build();
        when(refundCaseMapper.selectByRefundCaseNo("IRC_1")).thenReturn(refundCase);
        when(paymentFlowMapper.selectById(3L)).thenReturn(paymentFlow(InstorePaymentFlowStatus.PAID));
        when(instoreOrderMapper.selectById(2L)).thenReturn(instoreOrder(InstoreOrderStatus.COMPLETED));
        when(orderMapper.selectById(1L)).thenReturn(buyerOrder());
        assertThatThrownBy(() -> lifecycle.applyBuyerRefundResult(
                "IRC_1", "NOT_A_STATUS", 3_000L, null, null, TRACE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid refund status");
    }

    private void stubNewHeadCreate() {
        when(paymentFlowMapper.selectByChannelTrade(CHANNEL, TRADE_ID)).thenReturn(null);
        when(bindingMapper.selectByProjectUser(101L, "buyer-user-1", CHANNEL)).thenReturn(activeBinding());
        when(supplierChannelBindingMapper.selectCurrentEnabled(CHANNEL, "MERCHANT1")).thenReturn(channelBinding());
        when(supplierMapper.selectById(31L)).thenReturn(supplier());
        when(instoreOrderMapper.selectByChannelOrder(CHANNEL, ORDER_ID)).thenReturn(null);
        when(businessNoGenerator.next(BusinessNoPrefixes.ISC)).thenReturn("ISC_1");
        when(businessNoGenerator.next(BusinessNoPrefixes.IPF)).thenReturn("IPF_FLOW", "IPF_OTN");
        when(businessNoGenerator.next(BusinessNoPrefixes.IOP)).thenReturn("IOP_1");
        doAnswer(setId(1L)).when(orderMapper).insert(any(SupplyChainOrderDO.class));
        doAnswer(setId(2L)).when(instoreOrderMapper).insert(any(InstoreChannelOrderDO.class));
        when(orderMapper.attachDetail(eq(1L), eq(2L), eq(0L), any())).thenReturn(1);
        when(orderMapper.advanceDetailVersion(eq(1L), eq(0L), eq(1L), any())).thenReturn(1);
        when(paymentFlowMapper.countByInstoreOrder(2L)).thenReturn(0L);
        doAnswer(setId(3L)).when(paymentFlowMapper).insert(any(InstorePaymentFlowDO.class));
        when(fundReservationService.freeze(any())).thenReturn(new FundReservationService.ReservationSnapshot(
                "FR_1", 1001L, FundReferenceType.INSTORE_ORDER.code(), "IPF_FLOW",
                10_000L, 0L, 0L, 10_000L, (short) 10, 0L));
        doAnswer(setId(40L)).when(operationMapper).insert(any(InstoreOperationDO.class));
        when(outboxRepository.appendLocal(anyString(), anyString(), anyLong(), anyString(), anyString(),
                anyMap(), anyString(), anyString(), anyString(), any())).thenReturn("EVT_1");
    }

    private static org.mockito.stubbing.Answer<Integer> setId(long id) {
        return invocation -> {
            Object entity = invocation.getArgument(0);
            entity.getClass().getMethod("setId", Long.class).invoke(entity, id);
            return 1;
        };
    }

    private static InstoreChannelLifecycle.CreatePaymentOrderCommand createCommand(long payAmount, long totalAmount) {
        return new InstoreChannelLifecycle.CreatePaymentOrderCommand(
                CHANNEL, ORDER_ID, TRADE_ID, 1001L, 101L, 202L,
                "AIFULI_MALL", "tenant-a", "buyer-user-1", "buyer-user-1",
                payAmount, 1, totalAmount, "COMPANY", "MERCHANT1",
                "SHOP1", "Shop", "subject", TRACE);
    }

    private static InstoreChannelUserBindingDO activeBinding() {
        return InstoreChannelUserBindingDO.builder()
                .id(11L).buyerId(1001L).projectId(101L).sourceSystem("AIFULI_MALL")
                .externalTenantId("tenant-a").buyerUserId("buyer-user-1")
                .channelCode(CHANNEL).status((short) 10).version(0L).build();
    }

    private static SupplierChannelBindingDO channelBinding() {
        return SupplierChannelBindingDO.builder().id(21L).supplierId(31L).build();
    }

    private static SupplierDO supplier() {
        return SupplierDO.builder().id(31L).supplierCode("RTM").supplierName("大润发").build();
    }

    private static InstorePaymentFlowDO paymentFlow(InstorePaymentFlowStatus status) {
        return InstorePaymentFlowDO.builder()
                .id(3L).paymentFlowNo("IPF_1").instoreOrderId(2L).orderId(1L)
                .channelCode(CHANNEL).tradeId(TRADE_ID).payAmount(10_000L)
                .payStatus(status.getCode()).outTradeNo("OTN_1").fundReservationNo("FR_1")
                .version(0L).build();
    }

    private static byte[] refundFingerprint(String tradeId, String refundType, long refundAmount) {
        String canonical = CHANNEL + "|RFN001|" + tradeId + "|" + refundType + "|" + refundAmount;
        return InstoreChannelLifecycle.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static InstoreOperationDO preorderOperation() {
        return InstoreOperationDO.builder()
                .id(40L).operationNo("IOP_1").paymentFlowId(3L)
                .operationType(InstoreOperationType.PREORDER.name())
                .status(InstoreOperationStatus.PENDING.getCode()).version(0L).build();
    }

    private static InstoreChannelOrderDO instoreOrder(InstoreOrderStatus status) {
        return InstoreChannelOrderDO.builder()
                .id(2L).instoreOrderNo("ISC_1").orderId(1L).buyerId(1001L)
                .projectId(101L).cashierProfileVersionId(202L)
                .sourceSystem("AIFULI_MALL").externalTenantId("tenant-a").buyerUserId("buyer-user-1")
                .channelCode(CHANNEL).channelOrderId(ORDER_ID).supplierId(31L)
                .supplierChannelBindingId(21L).supplierCodeSnapshot("RTM").supplierNameSnapshot("大润发")
                .totalAmount(10_000L).totalPaidAmount(0L).totalChannelRefundAmount(0L)
                .orderStatus(status.getCode()).version(0L).build();
    }

    private static SupplyChainOrderDO buyerOrder() {
        return buyerOrder(0L);
    }

    private static SupplyChainOrderDO buyerOrder(long detailVersion) {
        return SupplyChainOrderDO.builder()
                .id(1L).orderNo("SC_1").buyerOrderNo(ORDER_ID)
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_INSTORE)
                .detailVersion(detailVersion).build();
    }
}
