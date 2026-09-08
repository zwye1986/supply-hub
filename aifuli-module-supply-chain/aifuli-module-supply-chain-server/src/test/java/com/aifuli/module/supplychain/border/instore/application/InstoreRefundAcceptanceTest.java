package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreOperationDO;
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
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryPolicyRegistry;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.time.LocalDateTime;

import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.BUYER_REFUNDING;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.QUEUED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_EXCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款受理串行化单测：在途防超退、活动退款互斥、抢占派发与取消转退款。
 *
 * <p>计划基线：{@code docs/superpowers/plans/2026-08-23-instore-refund-serialization.md}（Task 4）；
 * 设计基线：{@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 4 条。
 * 与计划的差异：mock 的 insert 不模拟 MyBatis Plus 主键回填，用 doAnswer 显式回填 Case ID。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreRefundAcceptanceTest {

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
    @Mock private InstoreRefundSequencer refundSequencer;
    @Mock private SupplyChainDatabaseClock databaseClock;
    @InjectMocks private InstoreChannelLifecycle lifecycle;

    @BeforeEach
    void setUpDatabaseClock() {
        lenient().when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
    }

    private static InstorePaymentFlowDO flow(long id) {
        return InstorePaymentFlowDO.builder().id(id).instoreOrderId(7L).orderId(11L)
                .channelCode("RT_MART").tradeId("T1").outTradeNo("OT1").payAmount(1000L)
                .payStatus(InstorePaymentFlowStatus.PAID.getCode()).version(0L).build();
    }

    private static InstoreChannelOrderDO order(long paid, long refunded) {
        return InstoreChannelOrderDO.builder().id(7L).orderId(11L).buyerId(5L)
                .instoreOrderNo("ISC_1").projectId(1L).cashierProfileVersionId(2L)
                .totalPaidAmount(paid).totalChannelRefundAmount(refunded)
                .orderStatus(InstoreOrderStatus.COMPLETED.getCode()).version(0L).build();
    }

    private static InstoreRefundCaseDO caseDO(long id, short status, Byte flag, long amount) {
        return InstoreRefundCaseDO.builder().id(id).refundCaseNo("IRC_" + id)
                .instoreOrderId(7L).paymentFlowId(3L).orderId(11L).buyerId(5L)
                .channelCode("RT_MART").channelRefundNo("R" + id).refundSequence(1)
                .refundType("CHANNEL_REFUND").channelRefundAmount(amount)
                .status(status).buyerRefundingFlag(flag).version(0L).build();
    }

    private static SupplyChainOrderDO buyerOrder() {
        return SupplyChainOrderDO.builder().id(11L).orderNo("SC_1").orderType("INSTORE")
                .detailVersion(0L).build();
    }

    private void stubCommon() {
        lenient().when(paymentFlowMapper.selectByChannelTrade("RT_MART", "T1")).thenReturn(flow(3L));
        lenient().when(instoreOrderMapper.selectByIdForUpdate(7L)).thenReturn(order(1000L, 0L));
        lenient().when(refundSequencer.nextSequence(3L)).thenReturn(1);
        lenient().when(businessNoGenerator.next(anyString())).thenReturn("IRC_NEW");
    }

    private InstoreChannelLifecycle.AcceptRefundCommand acceptCommand(long amount) {
        return new InstoreChannelLifecycle.AcceptRefundCommand(
                1L, "RT_MART", "R_NEW", "T1", "CHANNEL_REFUND", amount, "trace-1");
    }

    @Test
    void rejectsRefundExceedingPaidMinusInflightAmounts() {
        stubCommon();
        when(refundCaseMapper.selectByChannelRefundNo("RT_MART", "R_NEW")).thenReturn(null);
        // 已有排队中 300 + 已成功累计 200，可退 = 1000 - 200 - 300 = 500
        lenient().when(instoreOrderMapper.selectByIdForUpdate(7L)).thenReturn(order(1000L, 200L));
        when(refundCaseMapper.selectInflightByFlow(3L))
                .thenReturn(List.of(caseDO(1L, QUEUED.getCode(), null, 300L)));

        assertThatThrownBy(() -> lifecycle.acceptRefund(acceptCommand(501L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(INSTORE_REFUND_AMOUNT_EXCEEDED.getMsg());
        verify(refundCaseMapper, never()).insert(any(InstoreRefundCaseDO.class));
    }

    @Test
    void queuesBehindActiveRefundWithoutDispatch() {
        stubCommon();
        when(refundCaseMapper.selectByChannelRefundNo("RT_MART", "R_NEW")).thenReturn(null);
        when(refundCaseMapper.selectInflightByFlow(3L)).thenReturn(List.of());
        when(refundCaseMapper.selectActiveByFlow(3L))
                .thenReturn(caseDO(1L, BUYER_REFUNDING.getCode(), (byte) 1, 300L));
        when(refundCaseMapper.insert(any(InstoreRefundCaseDO.class))).thenReturn(1);

        InstoreChannelLifecycle.AcceptRefundOutcome outcome = lifecycle.acceptRefund(acceptCommand(300L));

        assertThat(outcome.claimedForDispatch()).isFalse();
        assertThat(outcome.refundStatus()).isEqualTo(QUEUED.getCode());
        verify(refundCaseMapper, never()).claimDispatch(anyLong(), anyLong(), any());
        verify(operationMapper, never()).insert(any(InstoreOperationDO.class));
    }

    @Test
    void claimsDispatchWhenNoActiveRefund() {
        stubCommon();
        when(refundCaseMapper.selectByChannelRefundNo("RT_MART", "R_NEW")).thenReturn(null);
        when(refundCaseMapper.selectInflightByFlow(3L)).thenReturn(List.of());
        when(refundCaseMapper.selectActiveByFlow(3L)).thenReturn(null);
        doAnswer(invocation -> {
            InstoreRefundCaseDO inserted = invocation.getArgument(0);
            inserted.setId(11L);
            return 1;
        }).when(refundCaseMapper).insert(any(InstoreRefundCaseDO.class));
        when(refundCaseMapper.claimDispatch(anyLong(), eq(0L), any())).thenReturn(1);

        InstoreChannelLifecycle.AcceptRefundOutcome outcome = lifecycle.acceptRefund(acceptCommand(300L));

        assertThat(outcome.claimedForDispatch()).isTrue();
        assertThat(outcome.refundStatus()).isEqualTo(BUYER_REFUNDING.getCode());
        assertThat(outcome.operationNo()).isNotBlank();
        verify(instoreOrderMapper).setActiveRefundCase(eq(7L), anyLong(), anyLong(), any());
        ArgumentCaptor<InstoreOperationDO> opCaptor = ArgumentCaptor.forClass(InstoreOperationDO.class);
        verify(operationMapper).insert(opCaptor.capture());
        assertThat(opCaptor.getValue().getOperationType()).isEqualTo("BUYER_REFUND");
        assertThat(opCaptor.getValue().getRefundCaseId()).isNotNull();
    }

    @Test
    void idempotentReplayOfQueuedCaseClaimsDispatch() {
        stubCommon();
        InstoreRefundCaseDO existing = caseDO(9L, QUEUED.getCode(), null, 300L);
        existing.setChannelRefundNo("R_NEW");
        existing.setRequestFingerprint(refundFingerprint("R_NEW", 300L));
        when(refundCaseMapper.selectByChannelRefundNo("RT_MART", "R_NEW")).thenReturn(existing);
        when(refundCaseMapper.selectActiveByFlow(3L)).thenReturn(null);
        when(refundCaseMapper.claimDispatch(eq(9L), eq(0L), any())).thenReturn(1);

        InstoreChannelLifecycle.AcceptRefundOutcome outcome = lifecycle.acceptRefund(acceptCommand(300L));

        assertThat(outcome.claimedForDispatch()).isTrue();
        assertThat(outcome.refundCaseNo()).isEqualTo("IRC_9");
        verify(refundCaseMapper, never()).insert(any(InstoreRefundCaseDO.class));
    }

    private static byte[] refundFingerprint(String channelRefundNo, long amount) {
        String canonical = "RT_MART|" + channelRefundNo + "|T1|CHANNEL_REFUND|" + amount;
        return InstoreChannelLifecycle.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void cancelPaidFlowClaimsCancelRefundDispatch() {
        when(paymentFlowMapper.selectByChannelTrade("RT_MART", "T1")).thenReturn(flow(3L));
        when(instoreOrderMapper.selectById(7L)).thenReturn(order(1000L, 0L));
        when(orderMapper.selectById(11L)).thenReturn(buyerOrder());
        when(refundCaseMapper.selectByChannelRefundNo(eq("RT_MART"), anyString())).thenReturn(null);
        when(refundCaseMapper.selectByFlowAndType(3L, "CANCEL_REFUND")).thenReturn(null);
        when(refundCaseMapper.selectByFlowAndType(3L, "CHANNEL_REFUND")).thenReturn(null);
        when(refundSequencer.nextSequence(3L)).thenReturn(1);
        when(businessNoGenerator.next(anyString())).thenReturn("IRC_CANCEL");
        doAnswer(invocation -> {
            InstoreRefundCaseDO inserted = invocation.getArgument(0);
            inserted.setId(12L);
            return 1;
        }).when(refundCaseMapper).insert(any(InstoreRefundCaseDO.class));
        when(refundCaseMapper.selectActiveByFlow(3L)).thenReturn(null);
        when(refundCaseMapper.claimDispatch(anyLong(), eq(0L), any())).thenReturn(1);

        InstoreChannelLifecycle.CancelPaymentFlowResult result = lifecycle.cancelPaymentFlow(
                new InstoreChannelLifecycle.CancelPaymentFlowCommand(1L, "RT_MART", "T1", "trace-1"));

        assertThat(result.convertedToRefund()).isTrue();
        assertThat(result.claimedForDispatch()).isTrue();
        assertThat(result.refundCaseNo()).isEqualTo("IRC_CANCEL");
    }
}
