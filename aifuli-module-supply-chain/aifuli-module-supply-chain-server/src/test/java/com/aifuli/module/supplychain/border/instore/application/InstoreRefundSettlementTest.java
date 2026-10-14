package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelUserBindingMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreOperationMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreSupplierOrderMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.InstoreOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountReturnService;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierChannelBindingMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.LocalDateTime;

import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.BUYER_REFUNDING;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.MANUAL;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.QUEUED;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.SUCCESS;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款结果收敛单测：金额校验、账户返还、累计、售后投影与下一个排队派发。
 *
 * <p>计划基线：{@code docs/superpowers/plans/2026-08-23-instore-refund-serialization.md}（Task 5）；
 * 设计基线：{@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 6/7 条。
 * 与计划的差异：补全全部构造器依赖 mock（{@code @InjectMocks} 对未提供的构造参数注入 null，
 * 会在终态收尾写 Outbox/状态日志时 NPE）。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreRefundSettlementTest {

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
    @Mock private OrderSummaryPolicyRegistry orderSummaryPolicyRegistry;
    @Mock private BuyerAccountReturnService accountReturnService;
    @Mock private InstoreOrderSummaryPolicy summaryPolicy;
    @Mock private SupplyChainDatabaseClock databaseClock;
    @InjectMocks private InstoreChannelLifecycle lifecycle;

    @BeforeEach
    void setUpDatabaseClock() {
        lenient().when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
    }

    private static InstoreRefundCaseDO caseDO(short status, Byte flag) {
        return InstoreRefundCaseDO.builder().id(9L).refundCaseNo("IRC_9")
                .instoreOrderId(7L).paymentFlowId(3L).orderId(11L).buyerId(5L)
                .channelCode("RT_MART").channelRefundNo("R9").refundSequence(1)
                .refundType("CHANNEL_REFUND").channelRefundAmount(300L)
                .status(status).buyerRefundingFlag(flag).version(4L).build();
    }

    private void stubScope(InstoreRefundCaseDO refundCase, InstoreChannelOrderDO head,
                           SupplyChainOrderDO order) {
        lenient().when(refundCaseMapper.selectByRefundCaseNo("IRC_9")).thenReturn(refundCase);
        lenient().when(paymentFlowMapper.selectById(3L)).thenReturn(
                InstorePaymentFlowDO.builder().id(3L).instoreOrderId(7L).orderId(11L)
                        .channelCode("RT_MART").tradeId("T1").outTradeNo("OT1")
                        .payAmount(1000L).payStatus(InstorePaymentFlowStatus.PAID.getCode())
                        .fundReservationNo("FR_ORIGINAL").version(0L).build());
        lenient().when(fundReservationService.requireBuyerIdForReservation("FR_ORIGINAL")).thenReturn(6L);
        lenient().when(instoreOrderMapper.selectById(7L)).thenReturn(head);
        lenient().when(orderMapper.selectById(11L)).thenReturn(order);
    }

    private InstoreChannelOrderDO head(long paid, long refunded) {
        return InstoreChannelOrderDO.builder().id(7L).orderId(11L).buyerId(5L)
                .instoreOrderNo("ISC_1").projectId(1L).cashierProfileVersionId(2L)
                .totalPaidAmount(paid).totalChannelRefundAmount(refunded)
                .totalBuyerRefundAmount(0L).totalAccountReturnAmount(0L)
                .activeRefundCaseId(9L)
                .orderStatus(InstoreOrderStatus.COMPLETED.getCode()).version(6L).build();
    }

    private SupplyChainOrderDO order() {
        return SupplyChainOrderDO.builder().id(11L).orderNo("SC_1").orderType("INSTORE")
                .orderStatus((short) 30).aftersaleStatus((short) 0).detailVersion(2L).build();
    }

    @Test
    void successReturnsFundsAccumulatesAndProjectsAftersale() {
        InstoreRefundCaseDO refundCase = caseDO(BUYER_REFUNDING.getCode(), (byte) 1);
        stubScope(refundCase, head(1000L, 0L), order());
        when(refundCaseMapper.completeRefund(eq(9L), eq(4L), eq(SUCCESS.getCode()),
                eq(300L), eq(300L), eq("CRF_1"), eq((String) null), any())).thenReturn(1);
        when(instoreOrderMapper.accumulateRefundAmounts(eq(7L), eq(6L), eq(300L), eq(300L),
                eq(300L), any())).thenReturn(1);
        when(refundCaseMapper.selectOpenByFlow(3L)).thenReturn(List.of());
        when(summaryPolicy.decide(any(), any())).thenReturn(new OrderSummaryDecision(
                (short) 30, (short) 20, "STAGE"));
        when(orderMapper.advanceAftersaleStatus(eq(11L), eq(2L), eq(3L), eq((short) 20), any()))
                .thenReturn(1);
        when(refundCaseMapper.selectNextQueuedByFlow(3L)).thenReturn(null);

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 300L, "CRF_1", null, "trace-1");

        assertThat(outcome.amountMismatch()).isFalse();
        assertThat(outcome.finalStatus()).isEqualTo(SUCCESS.getCode());
        ArgumentCaptor<BuyerAccountReturnService.ReturnCommand> returnCaptor =
                ArgumentCaptor.forClass(BuyerAccountReturnService.ReturnCommand.class);
        verify(accountReturnService).returnFunds(returnCaptor.capture());
        assertThat(returnCaptor.getValue().buyerId()).isEqualTo(6L);
        assertThat(returnCaptor.getValue().refundCaseNo()).isEqualTo("IRC_9");
        assertThat(returnCaptor.getValue().amountCny()).isEqualTo(300L);
        assertThat(returnCaptor.getValue().orderNo()).isEqualTo("SC_1");
        assertThat(returnCaptor.getValue().referenceNo()).isEqualTo("ISC_1");
        verify(instoreOrderMapper).setActiveRefundCase(eq(7L), eq(7L), eq(null), any());
        verify(instoreOrderMapper).accumulateRefundAmounts(eq(7L), eq(6L), eq(300L), eq(300L), eq(300L), any());
    }

    @Test
    void amountMismatchGoesManualWithoutReturnOrAccumulation() {
        InstoreRefundCaseDO refundCase = caseDO(BUYER_REFUNDING.getCode(), (byte) 1);
        stubScope(refundCase, head(1000L, 0L), order());
        when(refundCaseMapper.completeRefund(eq(9L), eq(4L), eq(MANUAL.getCode()),
                eq(null), eq(null), eq("CRF_2"), eq("REFUND_AMOUNT_MISMATCH"), any())).thenReturn(1);
        when(refundCaseMapper.selectOpenByFlow(3L)).thenReturn(List.of(refundCase));
        when(summaryPolicy.decide(any(), any())).thenReturn(new OrderSummaryDecision(
                (short) 30, (short) 10, "STAGE"));
        when(orderMapper.advanceAftersaleStatus(eq(11L), eq(2L), eq(3L), eq((short) 10), any()))
                .thenReturn(1);
        when(refundCaseMapper.selectNextQueuedByFlow(3L)).thenReturn(null);

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 200L, "CRF_2", null, "trace-1");

        assertThat(outcome.amountMismatch()).isTrue();
        assertThat(outcome.finalStatus()).isEqualTo(MANUAL.getCode());
        verify(accountReturnService, never()).returnFunds(any());
        verify(instoreOrderMapper, never()).accumulateRefundAmounts(anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void nullActualAmountGoesManual() {
        InstoreRefundCaseDO refundCase = caseDO(BUYER_REFUNDING.getCode(), (byte) 1);
        stubScope(refundCase, head(1000L, 0L), order());
        when(refundCaseMapper.completeRefund(eq(9L), eq(4L), eq(MANUAL.getCode()),
                eq(null), eq(null), eq(null), eq("REFUND_AMOUNT_MISMATCH"), any())).thenReturn(1);
        when(refundCaseMapper.selectOpenByFlow(3L)).thenReturn(List.of(refundCase));
        when(summaryPolicy.decide(any(), any())).thenReturn(new OrderSummaryDecision(
                (short) 30, (short) 10, "STAGE"));
        when(orderMapper.advanceAftersaleStatus(eq(11L), eq(2L), eq(3L), eq((short) 10), any()))
                .thenReturn(1);
        when(refundCaseMapper.selectNextQueuedByFlow(3L)).thenReturn(null);

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", null, null, null, "trace-1");

        assertThat(outcome.amountMismatch()).isTrue();
        verify(accountReturnService, never()).returnFunds(any());
    }

    @Test
    void failedReleasesLockAndDispatchesNextQueuedCase() {
        InstoreRefundCaseDO refundCase = caseDO(BUYER_REFUNDING.getCode(), (byte) 1);
        stubScope(refundCase, head(1000L, 0L), order());
        when(refundCaseMapper.completeRefund(eq(9L), eq(4L), eq(FAILED.getCode()),
                eq(null), eq(null), eq(null), eq("REJECTED"), any())).thenReturn(1);
        when(refundCaseMapper.selectOpenByFlow(3L)).thenReturn(List.of());
        when(summaryPolicy.decide(any(), any())).thenReturn(new OrderSummaryDecision(
                (short) 30, (short) 0, "STAGE"));
        when(orderMapper.advanceAftersaleStatus(eq(11L), eq(2L), eq(3L), eq((short) 0), any()))
                .thenReturn(1);
        InstoreRefundCaseDO next = InstoreRefundCaseDO.builder().id(10L).refundCaseNo("IRC_10")
                .instoreOrderId(7L).paymentFlowId(3L).orderId(11L).buyerId(5L)
                .channelCode("RT_MART").channelRefundNo("R10").refundSequence(2)
                .refundType("CHANNEL_REFUND").channelRefundAmount(200L)
                .status(QUEUED.getCode()).buyerRefundingFlag(null).version(0L).build();
        when(refundCaseMapper.selectNextQueuedByFlow(3L)).thenReturn(next);
        when(refundCaseMapper.claimDispatch(eq(10L), eq(0L), any())).thenReturn(1);
        when(businessNoGenerator.next(BusinessNoPrefixes.IOP)).thenReturn("IOP_NEXT");

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "FAILED", null, null, "REJECTED", "trace-1");

        assertThat(outcome.finalStatus()).isEqualTo(FAILED.getCode());
        assertThat(outcome.nextClaimedRefundCaseNo()).isEqualTo("IRC_10");
        verify(instoreOrderMapper).setActiveRefundCase(eq(7L), eq(7L), eq(10L), any());
    }

    @Test
    void terminalCaseReplayIsIdempotentNoop() {
        InstoreRefundCaseDO refundCase = caseDO(SUCCESS.getCode(), null);
        stubScope(refundCase, head(1000L, 300L), order());

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 300L, "CRF_1", null, "trace-1");

        assertThat(outcome.finalStatus()).isEqualTo(SUCCESS.getCode());
        verify(accountReturnService, never()).returnFunds(any());
        verify(refundCaseMapper, never()).completeRefund(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyShort(), any(), any(), any(), any(), any());
    }

    @Test
    void processingKeepsCaseInFlightWithoutSideEffects() {
        InstoreRefundCaseDO refundCase = caseDO(BUYER_REFUNDING.getCode(), (byte) 1);
        stubScope(refundCase, head(1000L, 0L), order());

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "PROCESSING", null, null, null, "trace-1");

        assertThat(outcome.finalStatus()).isEqualTo(BUYER_REFUNDING.getCode());
        verify(accountReturnService, never()).returnFunds(any());
        verify(refundCaseMapper, never()).completeRefund(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyShort(), any(), any(), any(), any(), any());
    }
}
