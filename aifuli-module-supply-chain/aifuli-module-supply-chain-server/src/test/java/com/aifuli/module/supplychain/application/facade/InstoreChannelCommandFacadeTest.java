package com.aifuli.module.supplychain.application.facade;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.application.InstoreChannelLifecycle;
import com.aifuli.module.supplychain.border.instore.application.InstoreChannelUserBindingResolver;
import com.aifuli.module.supplychain.border.instore.application.InstorePaymentConvergenceService;
import com.aifuli.module.supplychain.border.instore.application.InstoreRefundConvergenceService;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
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

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_CHANNEL_USER_BINDING_MISSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到店购建单 Facade 单测：网络调用必须位于 Lifecycle 本地事务之间，并使用冻结的项目路由。
 *
 * <p>详见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreChannelCommandFacadeTest {

    @Mock private InstoreChannelLifecycle lifecycle;
    @Mock private BuyerIntegrationRouteResolver routeResolver;
    @Mock private BuyerCashierPort cashierPort;
    @Mock private InstorePaymentConvergenceService convergenceService;
    @Mock private InstoreRefundConvergenceService refundConvergenceService;
    @Mock private InstoreChannelUserBindingResolver bindingResolver;

    private InstoreChannelCommandFacade facade;

    @BeforeEach
    void setUp() {
        facade = new InstoreChannelCommandFacade(lifecycle, routeResolver, cashierPort,
                convergenceService, refundConvergenceService, bindingResolver);
    }

    @Test
    void createsCashierOrderWithFrozenRouteAndOpaqueBuyerUserId() {
        BuyerIntegrationRoute route = cashierRoute();
        when(routeResolver.resolveFrozen(101L, 202L, BuyerIntegrationCapabilityCode.CASHIER)).thenReturn(route);
        when(lifecycle.createPaymentOrder(any())).thenReturn(prepared(false, null,
                InstorePaymentFlowStatus.PENDING.getCode()));
        when(lifecycle.claimPaymentPreparation("IOP_1", 0L))
                .thenReturn(new InstoreChannelLifecycle.ClaimResult(true, 3L, 1L));
        BuyerCashierPort.CreateCashierOrderResult created = new BuyerCashierPort.CreateCashierOrderResult(
                BuyerCashierPort.CashierOrderStatus.CREATED, "CASHIER_1", "https://cashier/pay",
                10_000L, null, OffsetDateTime.parse("2026-08-21T01:00:00Z"));
        when(cashierPort.createCashierOrder(any())).thenReturn(created);

        InstoreChannelCommandFacade.PaymentTicket result = facade.createPayment(command());

        ArgumentCaptor<InstoreChannelLifecycle.CreatePaymentOrderCommand> lifecycleCommand =
                ArgumentCaptor.forClass(InstoreChannelLifecycle.CreatePaymentOrderCommand.class);
        verify(lifecycle).createPaymentOrder(lifecycleCommand.capture());
        assertThat(lifecycleCommand.getValue().projectId()).isEqualTo(101L);
        assertThat(lifecycleCommand.getValue().cashierProfileVersionId()).isEqualTo(202L);
        assertThat(lifecycleCommand.getValue().sourceSystem()).isEqualTo("AIFULI_MALL");
        assertThat(lifecycleCommand.getValue().externalTenantId()).isEqualTo("tenant-a");

        ArgumentCaptor<BuyerCashierPort.CreateCashierOrderCommand> cashierCommand =
                ArgumentCaptor.forClass(BuyerCashierPort.CreateCashierOrderCommand.class);
        verify(cashierPort).createCashierOrder(cashierCommand.capture());
        assertThat(cashierCommand.getValue().route()).isSameAs(route);
        assertThat(cashierCommand.getValue().buyerUserId()).isEqualTo("opaque-user");
        assertThat(cashierCommand.getValue().outTradeNo()).isEqualTo("IPF_OTN");
        verify(lifecycle).recordCashierOrder("IOP_1", 1L, created);
        assertThat(result.cashierUrl()).isEqualTo("https://cashier/pay");
        assertThat(result.payStatus()).isEqualTo(InstorePaymentFlowStatus.PROCESSING.getCode());
    }

    @Test
    void idempotentReplayReturnsStoredUrlWithoutClaimOrExternalCall() {
        when(routeResolver.resolveFrozen(101L, 202L, BuyerIntegrationCapabilityCode.CASHIER))
                .thenReturn(cashierRoute());
        when(lifecycle.createPaymentOrder(any())).thenReturn(prepared(
                true, "https://cashier/existing", InstorePaymentFlowStatus.PROCESSING.getCode()));

        InstoreChannelCommandFacade.PaymentTicket result = facade.createPayment(command());

        assertThat(result.outTradeNo()).isEqualTo("IPF_OTN");
        assertThat(result.cashierUrl()).isEqualTo("https://cashier/existing");
        verify(lifecycle, never()).claimPaymentPreparation(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(cashierPort, never()).createCashierOrder(any());
    }

    @Test
    void claimLossDoesNotCallCashierAgain() {
        when(routeResolver.resolveFrozen(101L, 202L, BuyerIntegrationCapabilityCode.CASHIER))
                .thenReturn(cashierRoute());
        when(lifecycle.createPaymentOrder(any())).thenReturn(prepared(false, null,
                InstorePaymentFlowStatus.PENDING.getCode()));
        when(lifecycle.claimPaymentPreparation("IOP_1", 0L))
                .thenReturn(new InstoreChannelLifecycle.ClaimResult(false, 3L, 1L));

        InstoreChannelCommandFacade.PaymentTicket result = facade.createPayment(command());

        assertThat(result.outTradeNo()).isEqualTo("IPF_OTN");
        verify(cashierPort, never()).createCashierOrder(any());
        verify(lifecycle, never()).recordCashierOrder(any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void cashierFailureKeepsOriginalBusinessNumberForAuthoritativeRecovery() {
        when(routeResolver.resolveFrozen(101L, 202L, BuyerIntegrationCapabilityCode.CASHIER))
                .thenReturn(cashierRoute());
        when(lifecycle.createPaymentOrder(any())).thenReturn(prepared(false, null,
                InstorePaymentFlowStatus.PENDING.getCode()));
        when(lifecycle.claimPaymentPreparation("IOP_1", 0L))
                .thenReturn(new InstoreChannelLifecycle.ClaimResult(true, 3L, 1L));
        when(cashierPort.createCashierOrder(any())).thenThrow(new IllegalStateException("timeout"));

        InstoreChannelCommandFacade.PaymentTicket result = facade.createPayment(command());

        assertThat(result.outTradeNo()).isEqualTo("IPF_OTN");
        assertThat(result.cashierUrl()).isNull();
        assertThat(result.payStatus()).isEqualTo(InstorePaymentFlowStatus.PROCESSING.getCode());
        verify(lifecycle, never()).recordCashierOrder(any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void wireCreatePaymentOrderResolvesBindingAndCurrentCashierRoute() {
        when(bindingResolver.requireActiveBinding(101L, "opaque-user", "RT_MART"))
                .thenReturn(new InstoreChannelUserBindingResolver.BindingSnapshot(
                        1L, 1001L, 101L, "AIFULI_MALL", "tenant-a", "opaque-user", "RT_MART", 0L));
        BuyerIntegrationRoute route = cashierRoute();
        when(routeResolver.resolveCurrent(101L, BuyerIntegrationCapabilityCode.CASHIER)).thenReturn(route);
        // createPaymentOrder 委托既有 createPayment，其内部按 (projectId, profileVersionId) 冻结路由
        when(routeResolver.resolveFrozen(101L, 202L, BuyerIntegrationCapabilityCode.CASHIER))
                .thenReturn(route);
        when(lifecycle.createPaymentOrder(any())).thenReturn(prepared(false, null,
                InstorePaymentFlowStatus.PENDING.getCode()));
        when(lifecycle.claimPaymentPreparation("IOP_1", 0L))
                .thenReturn(new InstoreChannelLifecycle.ClaimResult(true, 3L, 1L));
        when(cashierPort.createCashierOrder(any())).thenReturn(new BuyerCashierPort.CreateCashierOrderResult(
                BuyerCashierPort.CashierOrderStatus.CREATED, "CASHIER_1", "https://cashier/pay",
                10_000L, null, OffsetDateTime.parse("2026-08-21T01:00:00Z")));

        InstoreChannelCommandFacade.PaymentTicket ticket = facade.createPaymentOrder(
                new InstoreChannelCommandFacade.WireCreatePaymentCommand(
                        101L, "RT_MART", "ORD001", "TRADE001", "opaque-user", 10_000L, 1,
                        10_000L, "COMPANY", "MERCHANT1", "SHOP1", "大润发门店", "到店购", "trace-1"));

        ArgumentCaptor<InstoreChannelLifecycle.CreatePaymentOrderCommand> lifecycleCommand =
                ArgumentCaptor.forClass(InstoreChannelLifecycle.CreatePaymentOrderCommand.class);
        verify(lifecycle).createPaymentOrder(lifecycleCommand.capture());
        assertThat(lifecycleCommand.getValue().buyerId()).isEqualTo(1001L);
        assertThat(lifecycleCommand.getValue().projectId()).isEqualTo(101L);
        assertThat(lifecycleCommand.getValue().cashierProfileVersionId()).isEqualTo(202L);
        assertThat(lifecycleCommand.getValue().buyerUserId()).isEqualTo("opaque-user");
        assertThat(lifecycleCommand.getValue().userCode()).isEqualTo("opaque-user");
        assertThat(lifecycleCommand.getValue().channelCode()).isEqualTo("RT_MART");
        assertThat(lifecycleCommand.getValue().channelOrderId()).isEqualTo("ORD001");
        assertThat(lifecycleCommand.getValue().tradeId()).isEqualTo("TRADE001");
        assertThat(lifecycleCommand.getValue().payAmount()).isEqualTo(10_000L);
        assertThat(lifecycleCommand.getValue().orderType()).isEqualTo(1);
        assertThat(lifecycleCommand.getValue().totalAmount()).isEqualTo(10_000L);
        assertThat(lifecycleCommand.getValue().companyCode()).isEqualTo("COMPANY");
        assertThat(lifecycleCommand.getValue().externalMerchantId()).isEqualTo("MERCHANT1");
        assertThat(lifecycleCommand.getValue().shopCode()).isEqualTo("SHOP1");
        assertThat(lifecycleCommand.getValue().shopName()).isEqualTo("大润发门店");
        assertThat(lifecycleCommand.getValue().subject()).isEqualTo("到店购");
        assertThat(lifecycleCommand.getValue().traceId()).isEqualTo("trace-1");
        ArgumentCaptor<BuyerCashierPort.CreateCashierOrderCommand> cashierCommand =
                ArgumentCaptor.forClass(BuyerCashierPort.CreateCashierOrderCommand.class);
        verify(cashierPort).createCashierOrder(cashierCommand.capture());
        assertThat(cashierCommand.getValue().route()).isSameAs(route);
        assertThat(ticket.cashierUrl()).isEqualTo("https://cashier/pay");
    }

    @Test
    void wireCreatePaymentOrderRejectsMissingBinding() {
        when(bindingResolver.requireActiveBinding(101L, "unknown-user", "RT_MART"))
                .thenThrow(new ServiceException(INSTORE_CHANNEL_USER_BINDING_MISSING));

        assertThatThrownBy(() -> facade.createPaymentOrder(
                new InstoreChannelCommandFacade.WireCreatePaymentCommand(
                        101L, "RT_MART", "ORD001", "TRADE001", "unknown-user", 10_000L, 1,
                        10_000L, "COMPANY", "MERCHANT1", null, null, null, "trace-1")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(INSTORE_CHANNEL_USER_BINDING_MISSING.getCode());
        verify(lifecycle, never()).createPaymentOrder(any());
        verify(cashierPort, never()).createCashierOrder(any());
    }

    // 当前 CASHIER 档案版本未发布属内部配置错误：异常直接传播，且不触发 Lifecycle 建单。
    @Test
    void wireCreatePaymentOrderPropagatesCashierRouteResolutionFailure() {
        when(bindingResolver.requireActiveBinding(101L, "opaque-user", "RT_MART"))
                .thenReturn(new InstoreChannelUserBindingResolver.BindingSnapshot(
                        1L, 1001L, 101L, "AIFULI_MALL", "tenant-a", "opaque-user", "RT_MART", 0L));
        when(routeResolver.resolveCurrent(101L, BuyerIntegrationCapabilityCode.CASHIER))
                .thenThrow(new IllegalStateException("当前 CASHIER 档案版本未发布"));

        assertThatThrownBy(() -> facade.createPaymentOrder(
                new InstoreChannelCommandFacade.WireCreatePaymentCommand(
                        101L, "RT_MART", "ORD001", "TRADE001", "opaque-user", 10_000L, 1,
                        10_000L, "COMPANY", "MERCHANT1", "SHOP1", "大润发门店", "到店购", "trace-1")))
                .isInstanceOf(IllegalStateException.class);
        verify(lifecycle, never()).createPaymentOrder(any());
        verify(cashierPort, never()).createCashierOrder(any());
    }

    @Test
    void channelPaymentQueryDelegatesToUnifiedConvergenceService() {
        when(convergenceService.queryAndConvergePayment(
                101L, "RT_MART", "TRADE001", "trace-query")).thenReturn(new InstoreChannelLifecycle.PaymentStatusView(
                "IPF_1", "OTN_1", "TRADE001", InstorePaymentFlowStatus.PAID.getCode(),
                10_000L, 10_000L, "CASHIER_1", "TXN_1", null));

        InstoreChannelCommandFacade.PaymentStatusView result = facade.queryAndConvergePayment(
                new InstoreChannelCommandFacade.QueryPaymentCommand(101L, "RT_MART", "TRADE001", "trace-query"));

        assertThat(result.paymentFlowNo()).isEqualTo("IPF_1");
        assertThat(result.outTradeNo()).isEqualTo("OTN_1");
        assertThat(result.payStatus()).isEqualTo(InstorePaymentFlowStatus.PAID.getCode());
        assertThat(result.buyerPaidAmount()).isEqualTo(10_000L);
    }

    @Test
    void acceptRefundDispatchesClaimedCaseThroughConvergenceService() {
        when(lifecycle.acceptRefund(any())).thenReturn(new InstoreChannelLifecycle.AcceptRefundOutcome(
                "IRC_9", "OTN_9", true, "IOP_1", 1L, InstoreRefundStatus.BUYER_REFUNDING.getCode()));

        InstoreChannelCommandFacade.RefundAcceptance acceptance = facade.acceptRefund(
                new InstoreChannelCommandFacade.AcceptRefundCommand(
                        201L, "RT_MART", "R9", "T1", "CHANNEL_REFUND", 300L, "trace-1"));

        assertThat(acceptance.refundCaseNo()).isEqualTo("IRC_9");
        assertThat(acceptance.refundStatus()).isEqualTo(InstoreRefundStatus.BUYER_REFUNDING.getCode());
        assertThat(acceptance.claimedForDispatch()).isTrue();
        verify(refundConvergenceService).dispatchClaimedRefundCase("IRC_9", "IOP_1", "trace-1");
    }

    @Test
    void acceptRefundQueuedBehindActiveCaseSkipsExternalCall() {
        when(lifecycle.acceptRefund(any())).thenReturn(new InstoreChannelLifecycle.AcceptRefundOutcome(
                "IRC_10", null, false, null, 0L, InstoreRefundStatus.QUEUED.getCode()));

        InstoreChannelCommandFacade.RefundAcceptance acceptance = facade.acceptRefund(
                new InstoreChannelCommandFacade.AcceptRefundCommand(
                        201L, "RT_MART", "R10", "T1", "CHANNEL_REFUND", 200L, "trace-1"));

        assertThat(acceptance.claimedForDispatch()).isFalse();
        assertThat(acceptance.refundStatus()).isEqualTo(InstoreRefundStatus.QUEUED.getCode());
        verify(refundConvergenceService, never()).dispatchClaimedRefundCase(any(), any(), any());
    }

    @Test
    void cancelPaidFlowDispatchesClaimedCancelRefund() {
        when(lifecycle.cancelPaymentFlow(any())).thenReturn(new InstoreChannelLifecycle.CancelPaymentFlowResult(
                "OT1", "IRC_C", true, true, "IOP_C"));

        InstoreChannelCommandFacade.CancelPaymentTicket ticket = facade.cancelPaymentFlow(
                new InstoreChannelCommandFacade.CancelPaymentCommand(201L, "RT_MART", "T1", "trace-1"));

        assertThat(ticket.convertedToRefund()).isTrue();
        assertThat(ticket.claimedForDispatch()).isTrue();
        assertThat(ticket.refundCaseNo()).isEqualTo("IRC_C");
        verify(refundConvergenceService).dispatchClaimedRefundCase("IRC_C", "IOP_C", "trace-1");
    }

    @Test
    void queryAndConvergeRefundDelegatesToConvergenceService() {
        when(refundConvergenceService.queryAndConvergeRefund(1L, "RT_MART", "R9", "trace-1"))
                .thenReturn(new InstoreChannelLifecycle.RefundStatusView(
                        "IRC_9", "R9", "CHANNEL_REFUND",
                        InstoreRefundStatus.BUYER_REFUNDING.getCode(), 300L, null, null, null, null));

        InstoreChannelCommandFacade.RefundStatusView result = facade.queryAndConvergeRefund(
                new InstoreChannelCommandFacade.QueryRefundCommand(1L, "RT_MART", "R9", "trace-1"));

        assertThat(result.refundCaseNo()).isEqualTo("IRC_9");
        assertThat(result.status()).isEqualTo(InstoreRefundStatus.BUYER_REFUNDING.getCode());
        assertThat(result.channelRefundAmount()).isEqualTo(300L);
    }

    private static InstoreChannelCommandFacade.PreparePaymentCommand command() {
        return new InstoreChannelCommandFacade.PreparePaymentCommand(
                "RT_MART", "ORD001", "TRADE001", 1001L, 101L, 202L,
                "opaque-user", "opaque-user", 10_000L, 1, 10_000L,
                "COMPANY", "MERCHANT1", "SHOP1", "大润发门店", "到店购", "trace-1");
    }

    private static InstoreChannelLifecycle.PreparedPayment prepared(
            boolean replay, String cashierUrl, short payStatus) {
        return new InstoreChannelLifecycle.PreparedPayment(
                1L, "SC_1", 2L, "ISC_1", 3L, "IPF_FLOW", "IPF_OTN", "IOP_1", 0L,
                101L, 202L, "opaque-user", "到店购", 10_000L, "FR_1", cashierUrl,
                payStatus, replay);
    }

    private static BuyerIntegrationRoute cashierRoute() {
        return new BuyerIntegrationRoute(
                101L, 202L, BuyerIntegrationCapabilityCode.CASHIER,
                "AIFULI_MALL", "tenant-a", "RPC", "SAAS_CASHIER_RPC", "v1", null);
    }
}
