package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款统一收敛单测：回调优先、项目归属校验、权威查询兜底与派发外呼。
 *
 * <p>计划基线：{@code docs/superpowers/plans/2026-08-23-instore-refund-serialization.md}（Task 6）；
 * 设计基线：{@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#51-saas-商城收银台编排}。
 * 与计划的差异：{@code BuyerIntegrationRoute} 为 9 字段构造（计划示例为 4 字段），
 * 按现有测试模式补全 capability/transportMode/adapterCode/contractVersion。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreRefundConvergenceTest {

    @Mock private InstoreRefundCaseMapper refundCaseMapper;
    @Mock private InstorePaymentFlowMapper paymentFlowMapper;
    @Mock private InstoreChannelOrderMapper orderMapper;
    @Mock private BuyerIntegrationRouteResolver routeResolver;
    @Mock private BuyerCashierPort cashierPort;
    @Mock private InstoreChannelLifecycle lifecycle;
    @InjectMocks private InstoreRefundConvergenceService service;

    private static BuyerIntegrationRoute cashierRoute() {
        return new BuyerIntegrationRoute(1L, 2L, BuyerIntegrationCapabilityCode.CASHIER,
                "SAAS", "TENANT_A", "HTTP", "adapter-saas", "v1", null);
    }

    private static InstoreRefundCaseDO refundCase(short status) {
        return InstoreRefundCaseDO.builder().id(9L).refundCaseNo("IRC_9")
                .instoreOrderId(7L).paymentFlowId(3L).orderId(11L).buyerId(5L)
                .channelCode("RT_MART").channelRefundNo("R9").refundSequence(1)
                .refundType("CHANNEL_REFUND").channelRefundAmount(300L)
                .status(status).buyerRefundingFlag((byte) 1).version(4L).build();
    }

    private static InstoreChannelOrderDO order() {
        return InstoreChannelOrderDO.builder().id(7L).orderId(11L).buyerId(5L)
                .instoreOrderNo("ISC_1").projectId(1L).cashierProfileVersionId(2L)
                .totalPaidAmount(1000L).totalChannelRefundAmount(0L)
                .orderStatus(InstoreOrderStatus.COMPLETED.getCode()).version(0L).build();
    }

    private void stubScope(InstoreRefundCaseDO refundCase) {
        lenient().when(refundCaseMapper.selectByRefundCaseNo("IRC_9")).thenReturn(refundCase);
        lenient().when(paymentFlowMapper.selectById(3L)).thenReturn(
                InstorePaymentFlowDO.builder().id(3L).instoreOrderId(7L).orderId(11L)
                        .channelCode("RT_MART").tradeId("T1").outTradeNo("OT1")
                        .payAmount(1000L).version(0L).build());
        lenient().when(orderMapper.selectById(7L)).thenReturn(order());
    }

    @Test
    void callbackConvergesDirectlyWithoutAuthoritativeQuery() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 300L, "CRF_1", null, "trace-1"))
                .thenReturn(new InstoreChannelLifecycle.RefundSettlementOutcome(
                        InstoreRefundStatus.SUCCESS.getCode(), false, false, null, null, 0L));

        service.confirmRefund(1L, "IRC_9", 300L, "CRF_1", "trace-1");

        verify(cashierPort, never()).queryRefund(any());
    }

    @Test
    void callbackRejectsDifferentProjectOwnership() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));

        assertThatThrownBy(() -> service.confirmRefund(99L, "IRC_9", 300L, "CRF_1", "trace-1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(INSTORE_TENANT_SCOPE_CONFLICT.getMsg());
        verify(lifecycle, never()).applyBuyerRefundResult(any(), any(), any(), any(), any(), any());
    }

    @Test
    void callbackAmountMismatchThrowsStableErrorAfterCaseSettledManual() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 200L, "CRF_1", null, "trace-1"))
                .thenReturn(new InstoreChannelLifecycle.RefundSettlementOutcome(
                        InstoreRefundStatus.MANUAL.getCode(), true, false, null, null, 0L));

        assertThatThrownBy(() -> service.confirmRefund(1L, "IRC_9", 200L, "CRF_1", "trace-1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(INSTORE_REFUND_AMOUNT_MISMATCH.getMsg());
    }

    @Test
    void terminalCaseSkipsAuthoritativeQuery() {
        stubScope(refundCase(InstoreRefundStatus.SUCCESS.getCode()));
        when(lifecycle.queryRefundStatus("RT_MART", "R9")).thenReturn(
                new InstoreChannelLifecycle.RefundStatusView("IRC_9", "R9", "CHANNEL_REFUND",
                        InstoreRefundStatus.SUCCESS.getCode(), 300L, 300L, null, null, null));

        service.queryAndConvergeRefundByRefundCaseNo("IRC_9", "trace-1");

        verify(cashierPort, never()).queryRefund(any());
    }

    @Test
    void authoritativeQuerySuccessConvergesWithOriginalRefundCaseNo() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(routeResolver.resolveFrozen(eq(1L), eq(2L), any())).thenReturn(cashierRoute());
        when(cashierPort.queryRefund(any())).thenReturn(new BuyerCashierPort.QueryRefundResult(
                BuyerCashierPort.RefundQueryStatus.SUCCEEDED, 300L, "CRF_1", null,
                OffsetDateTime.now(ZoneOffset.UTC)));
        when(lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 300L, "CRF_1", null, "trace-1"))
                .thenReturn(new InstoreChannelLifecycle.RefundSettlementOutcome(
                        InstoreRefundStatus.SUCCESS.getCode(), false, false, null, null, 0L));
        when(lifecycle.queryRefundStatus("RT_MART", "R9")).thenReturn(
                new InstoreChannelLifecycle.RefundStatusView("IRC_9", "R9", "CHANNEL_REFUND",
                        InstoreRefundStatus.SUCCESS.getCode(), 300L, 300L, null, null, null));

        service.queryAndConvergeRefundByRefundCaseNo("IRC_9", "trace-1");

        ArgumentCaptor<BuyerCashierPort.QueryRefundCommand> captor =
                ArgumentCaptor.forClass(BuyerCashierPort.QueryRefundCommand.class);
        verify(cashierPort).queryRefund(captor.capture());
        assertThat(captor.getValue().refundCaseNo()).isEqualTo("IRC_9");
    }

    @Test
    void dispatchClaimedRefundCallsCashierWithFrozenRouteAndOriginalNumbers() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(routeResolver.resolveFrozen(eq(1L), eq(2L), any())).thenReturn(cashierRoute());
        when(cashierPort.refund(any())).thenReturn(new BuyerCashierPort.RefundResult(
                BuyerCashierPort.RefundStatus.PROCESSING, null, null, null,
                OffsetDateTime.now(ZoneOffset.UTC)));

        service.dispatchClaimedRefundCase("IRC_9", "IOP_1", "trace-1");

        ArgumentCaptor<BuyerCashierPort.RefundCommand> captor =
                ArgumentCaptor.forClass(BuyerCashierPort.RefundCommand.class);
        verify(cashierPort).refund(captor.capture());
        assertThat(captor.getValue().refundCaseNo()).isEqualTo("IRC_9");
        assertThat(captor.getValue().outTradeNo()).isEqualTo("OT1");
        assertThat(captor.getValue().amountCny()).isEqualTo(300L);
        assertThat(captor.getValue().invocationId()).isEqualTo("IOP_1");
    }

    @Test
    void dispatchSucceededResponseConvergesImmediately() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(routeResolver.resolveFrozen(eq(1L), eq(2L), any())).thenReturn(cashierRoute());
        when(cashierPort.refund(any())).thenReturn(new BuyerCashierPort.RefundResult(
                BuyerCashierPort.RefundStatus.SUCCEEDED, 300L, "CRF_1", null,
                OffsetDateTime.now(ZoneOffset.UTC)));
        when(lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 300L, "CRF_1", null, "trace-1"))
                .thenReturn(new InstoreChannelLifecycle.RefundSettlementOutcome(
                        InstoreRefundStatus.SUCCESS.getCode(), false, false, null, null, 0L));

        service.dispatchClaimedRefundCase("IRC_9", "IOP_1", "trace-1");

        verify(lifecycle).applyBuyerRefundResult("IRC_9", "SUCCESS", 300L, "CRF_1", null, "trace-1");
    }

    @Test
    void dispatchCashierFailureKeepsOriginalRefundCaseNoForRecovery() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(routeResolver.resolveFrozen(eq(1L), eq(2L), any())).thenReturn(cashierRoute());
        when(cashierPort.refund(any())).thenThrow(new RuntimeException("cashier timeout"));

        service.dispatchClaimedRefundCase("IRC_9", "IOP_1", "trace-1");

        verify(lifecycle, never()).applyBuyerRefundResult(any(), any(), any(), any(), any(), any());
    }
}
