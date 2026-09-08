package com.aifuli.module.supplychain.application.facade;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.application.InstoreChannelLifecycle;
import com.aifuli.module.supplychain.border.instore.application.InstorePaymentConvergenceService;
import com.aifuli.module.supplychain.border.instore.application.InstoreRefundConvergenceService;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 支付回调、渠道查询与 Timer 共用原 outTradeNo 的收敛测试。 */
@ExtendWith(MockitoExtension.class)
class InstorePaymentConvergenceTest {

    @Mock private InstorePaymentFlowMapper paymentFlowMapper;
    @Mock private InstoreChannelOrderMapper orderMapper;
    @Mock private BuyerIntegrationRouteResolver routeResolver;
    @Mock private BuyerCashierPort cashierPort;
    @Mock private InstoreChannelLifecycle lifecycle;
    @Mock private InstoreRefundConvergenceService refundConvergenceService;

    private InstorePaymentConvergenceService convergenceService;
    private InstoreBuyerCashierCallbackFacade callbackFacade;

    @BeforeEach
    void setUp() {
        convergenceService = new InstorePaymentConvergenceService(
                paymentFlowMapper, orderMapper, routeResolver, cashierPort, lifecycle);
        callbackFacade = new InstoreBuyerCashierCallbackFacade(convergenceService, refundConvergenceService);
    }

    @Test
    void callbackConfirmsDirectlyWithoutAuthoritativeQuery() {
        stubCallbackScope(InstorePaymentFlowStatus.PROCESSING);

        callbackFacade.confirmPayment(new InstoreBuyerCashierCallbackFacade.PaymentCallbackCommand(
                101L, "OTN_1", 10_000L, "CASHIER_1", "TXN_1", "trace-callback"));

        verify(lifecycle).confirmBuyerPayment(
                "OTN_1", 10_000L, "CASHIER_1", "TXN_1", "trace-callback");
        verify(cashierPort, never()).queryPayment(any());
    }

    @Test
    void callbackRejectsDifferentProjectOwnership() {
        stubCallbackScope(InstorePaymentFlowStatus.PROCESSING);

        assertThatThrownBy(() -> callbackFacade.confirmPayment(
                new InstoreBuyerCashierCallbackFacade.PaymentCallbackCommand(
                        999L, "OTN_1", 10_000L, "CASHIER_1", "TXN_1", "trace-callback")))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT.getCode());
        verify(lifecycle, never()).confirmBuyerPayment(any(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }

    @Test
    void authoritativeSuccessUsesOriginalOutTradeNoAndCallbackConvergencePath() {
        stubQueryScope(InstorePaymentFlowStatus.PROCESSING);
        when(routeResolver.resolveFrozen(101L, 202L, BuyerIntegrationCapabilityCode.CASHIER))
                .thenReturn(cashierRoute());
        when(cashierPort.queryPayment(any())).thenReturn(new BuyerCashierPort.QueryPaymentResult(
                BuyerCashierPort.PaymentQueryStatus.SUCCEEDED, 10_000L,
                "CASHIER_1", "TXN_1", null, OffsetDateTime.parse("2026-08-21T02:00:00Z")));
        when(lifecycle.queryPaymentStatus("RT_MART", "TRADE001")).thenReturn(paidView());

        InstoreChannelLifecycle.PaymentStatusView result = convergenceService.queryAndConvergePayment(
                101L, "RT_MART", "TRADE001", "trace-query");

        ArgumentCaptor<BuyerCashierPort.QueryPaymentCommand> queryCaptor =
                ArgumentCaptor.forClass(BuyerCashierPort.QueryPaymentCommand.class);
        verify(cashierPort).queryPayment(queryCaptor.capture());
        assertThat(queryCaptor.getValue().outTradeNo()).isEqualTo("OTN_1");
        assertThat(queryCaptor.getValue().route()).isEqualTo(cashierRoute());
        verify(lifecycle).confirmBuyerPayment(
                "OTN_1", 10_000L, "CASHIER_1", "TXN_1", "trace-query");
        assertThat(result.payStatus()).isEqualTo(InstorePaymentFlowStatus.PAID.getCode());
    }

    @Test
    void pendingAuthoritativeResultDoesNotAdvanceLocalState() {
        stubQueryScope(InstorePaymentFlowStatus.PROCESSING);
        when(routeResolver.resolveFrozen(101L, 202L, BuyerIntegrationCapabilityCode.CASHIER))
                .thenReturn(cashierRoute());
        when(cashierPort.queryPayment(any())).thenReturn(new BuyerCashierPort.QueryPaymentResult(
                BuyerCashierPort.PaymentQueryStatus.PENDING, null,
                null, null, null, OffsetDateTime.parse("2026-08-21T02:00:00Z")));
        when(lifecycle.queryPaymentStatus("RT_MART", "TRADE001")).thenReturn(processingView());

        InstoreChannelLifecycle.PaymentStatusView result = convergenceService.queryAndConvergePayment(
                101L, "RT_MART", "TRADE001", "trace-query");

        verify(lifecycle, never()).confirmBuyerPayment(any(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
        assertThat(result.payStatus()).isEqualTo(InstorePaymentFlowStatus.PROCESSING.getCode());
    }

    @Test
    void localTerminalStateDoesNotCallCashierAgain() {
        stubQueryScope(InstorePaymentFlowStatus.PAID);
        when(lifecycle.queryPaymentStatus("RT_MART", "TRADE001")).thenReturn(paidView());

        convergenceService.queryAndConvergePayment(101L, "RT_MART", "TRADE001", "trace-query");

        verify(cashierPort, never()).queryPayment(any());
        verify(routeResolver, never()).resolveFrozen(any(), any(), any());
    }

    private void stubCallbackScope(InstorePaymentFlowStatus status) {
        InstorePaymentFlowDO flow = paymentFlow(status);
        when(paymentFlowMapper.selectByOutTradeNo("OTN_1")).thenReturn(flow);
        stubOrderScope();
    }

    private void stubQueryScope(InstorePaymentFlowStatus status) {
        InstorePaymentFlowDO flow = paymentFlow(status);
        when(paymentFlowMapper.selectByChannelTrade("RT_MART", "TRADE001")).thenReturn(flow);
        stubOrderScope();
    }

    private static InstorePaymentFlowDO paymentFlow(InstorePaymentFlowStatus status) {
        InstorePaymentFlowDO flow = InstorePaymentFlowDO.builder()
                .id(3L).instoreOrderId(2L).channelCode("RT_MART").tradeId("TRADE001")
                .outTradeNo("OTN_1").payAmount(10_000L).payStatus(status.getCode()).build();
        return flow;
    }

    private void stubOrderScope() {
        when(orderMapper.selectById(2L)).thenReturn(InstoreChannelOrderDO.builder()
                .id(2L).projectId(101L).cashierProfileVersionId(202L).build());
    }

    private static InstoreChannelLifecycle.PaymentStatusView processingView() {
        return new InstoreChannelLifecycle.PaymentStatusView(
                "IPF_1", "OTN_1", "TRADE001", InstorePaymentFlowStatus.PROCESSING.getCode(),
                10_000L, null, "CASHIER_1", null, null);
    }

    private static InstoreChannelLifecycle.PaymentStatusView paidView() {
        return new InstoreChannelLifecycle.PaymentStatusView(
                "IPF_1", "OTN_1", "TRADE001", InstorePaymentFlowStatus.PAID.getCode(),
                10_000L, 10_000L, "CASHIER_1", "TXN_1", null);
    }

    private static BuyerIntegrationRoute cashierRoute() {
        return new BuyerIntegrationRoute(
                101L, 202L, BuyerIntegrationCapabilityCode.CASHIER,
                "AIFULI_MALL", "tenant-a", "RPC", "SAAS_CASHIER_RPC", "v1", null);
    }
}
