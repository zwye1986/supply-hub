package com.aifuli.module.supplychain.interfaceapi.rpc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.AcceptRefundReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.CreatePaymentOrderReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentStatusRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentTicketRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryPaymentStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryRefundStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundAcceptanceRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundStatusRespDTO;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.ChannelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 到店购支付/退款命令 RPC 实现单测：上下文是唯一身份来源，Wire 映射逐字段对齐，
 * 入口/出口日志不泄漏 userCode 全值与 cashierUrl。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#3-interface-adapter-层}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreChannelCommandApiImplTest {

    @Mock private ProjectCallContextAccessor contextAccessor;
    @Mock private InstoreChannelCommandFacade facade;

    private InstoreChannelCommandApiImpl api;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger implLogger;

    @BeforeEach
    void setUp() {
        api = new InstoreChannelCommandApiImpl(contextAccessor, facade);
        implLogger = (Logger) LoggerFactory.getLogger(InstoreChannelCommandApiImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        implLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        implLogger.detachAppender(logAppender);
    }

    @Test
    void createPaymentOrderMapsAllFieldsAndDerivesIdentityFromChannelContext() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.createPaymentOrder(any())).thenReturn(new InstoreChannelCommandFacade.PaymentTicket(
                "SC_1", "IPF_OTN", "https://cashier/pay", InstorePaymentFlowStatus.PROCESSING.getCode()));
        CreatePaymentOrderReqDTO request = new CreatePaymentOrderReqDTO();
        request.setChannelOrderId("ORD001");
        request.setTradeId("TRADE001");
        request.setUserCode("opaque-user");
        request.setPayAmount(10_000L);
        request.setOrderType(1);
        request.setTotalAmount(10_000L);
        request.setCompanyCode("COMPANY");
        request.setExternalMerchantId("MERCHANT1");
        request.setShopCode("SHOP1");
        request.setShopName("大润发门店");
        request.setSubject("到店购");
        request.setTraceId("trace-1");

        CommonResult<PaymentTicketRespDTO> result = api.createPaymentOrder(request);

        ArgumentCaptor<InstoreChannelCommandFacade.WireCreatePaymentCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.WireCreatePaymentCommand.class);
        verify(facade).createPaymentOrder(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(captor.getValue().channelCode()).isEqualTo("RT_MART");
        assertThat(captor.getValue().channelOrderId()).isEqualTo("ORD001");
        assertThat(captor.getValue().tradeId()).isEqualTo("TRADE001");
        assertThat(captor.getValue().userCode()).isEqualTo("opaque-user");
        assertThat(captor.getValue().payAmount()).isEqualTo(10_000L);
        assertThat(captor.getValue().orderType()).isEqualTo(1);
        assertThat(captor.getValue().totalAmount()).isEqualTo(10_000L);
        assertThat(captor.getValue().companyCode()).isEqualTo("COMPANY");
        assertThat(captor.getValue().externalMerchantId()).isEqualTo("MERCHANT1");
        assertThat(captor.getValue().shopCode()).isEqualTo("SHOP1");
        assertThat(captor.getValue().shopName()).isEqualTo("大润发门店");
        assertThat(captor.getValue().subject()).isEqualTo("到店购");
        assertThat(captor.getValue().traceId()).isEqualTo("trace-1");
        assertThat(result.getData().getOrderNo()).isEqualTo("SC_1");
        assertThat(result.getData().getOutTradeNo()).isEqualTo("IPF_OTN");
        assertThat(result.getData().getCashierUrl()).isEqualTo("https://cashier/pay");
        assertThat(result.getData().getPayStatus())
                .isEqualTo((int) InstorePaymentFlowStatus.PROCESSING.getCode());
        assertLogsRedacted();
    }

    @Test
    void createPaymentOrderGeneratesTraceIdWhenBlank() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.createPaymentOrder(any())).thenReturn(new InstoreChannelCommandFacade.PaymentTicket(
                "SC_1", "IPF_OTN", null, InstorePaymentFlowStatus.PENDING.getCode()));
        CreatePaymentOrderReqDTO request = new CreatePaymentOrderReqDTO();
        request.setChannelOrderId("ORD001");
        request.setTradeId("TRADE001");
        request.setUserCode("opaque-user");
        request.setPayAmount(10_000L);
        request.setOrderType(1);
        request.setTotalAmount(10_000L);
        request.setCompanyCode("COMPANY");
        request.setExternalMerchantId("MERCHANT1");
        request.setTraceId(" ");

        api.createPaymentOrder(request);

        ArgumentCaptor<InstoreChannelCommandFacade.WireCreatePaymentCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.WireCreatePaymentCommand.class);
        verify(facade).createPaymentOrder(captor.capture());
        assertThat(captor.getValue().traceId()).isNotBlank();
    }

    @Test
    void queryPaymentStatusMapsFacadeViewToResponse() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.queryAndConvergePayment(any())).thenReturn(new InstoreChannelCommandFacade.PaymentStatusView(
                "IPF_1", "OTN_1", "TRADE001", InstorePaymentFlowStatus.PAID.getCode(),
                10_000L, 10_000L, "CASHIER_1", "TXN_1", null));
        QueryPaymentStatusReqDTO request = new QueryPaymentStatusReqDTO();
        request.setTradeId("TRADE001");
        request.setTraceId("trace-q");

        CommonResult<PaymentStatusRespDTO> result = api.queryPaymentStatus(request);

        ArgumentCaptor<InstoreChannelCommandFacade.QueryPaymentCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.QueryPaymentCommand.class);
        verify(facade).queryAndConvergePayment(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(captor.getValue().channelCode()).isEqualTo("RT_MART");
        assertThat(captor.getValue().tradeId()).isEqualTo("TRADE001");
        assertThat(result.getData().getPaymentFlowNo()).isEqualTo("IPF_1");
        assertThat(result.getData().getBuyerPaidAmount()).isEqualTo(10_000L);
        assertThat(result.getData().getPayStatus()).isEqualTo((int) InstorePaymentFlowStatus.PAID.getCode());
    }

    @Test
    void cancelPaymentFlowMapsCancelTicket() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.cancelPaymentFlow(any())).thenReturn(new InstoreChannelCommandFacade.CancelPaymentTicket(
                "OT1", "IRC_C", true, true));
        CancelPaymentFlowReqDTO request = new CancelPaymentFlowReqDTO();
        request.setTradeId("TRADE001");

        CommonResult<CancelPaymentFlowRespDTO> result = api.cancelPaymentFlow(request);

        ArgumentCaptor<InstoreChannelCommandFacade.CancelPaymentCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.CancelPaymentCommand.class);
        verify(facade).cancelPaymentFlow(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(captor.getValue().channelCode()).isEqualTo("RT_MART");
        assertThat(result.getData().getRefundCaseNo()).isEqualTo("IRC_C");
        assertThat(result.getData().getConvertedToRefund()).isTrue();
    }

    @Test
    void acceptRefundMapsRefundAcceptance() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.acceptRefund(any())).thenReturn(new InstoreChannelCommandFacade.RefundAcceptance(
                "IRC_9", null, InstoreRefundStatus.QUEUED.getCode(), false));
        AcceptRefundReqDTO request = new AcceptRefundReqDTO();
        request.setChannelRefundNo("R9");
        request.setTradeId("TRADE001");
        request.setRefundType("CHANNEL_REFUND");
        request.setChannelRefundAmount(300L);

        CommonResult<RefundAcceptanceRespDTO> result = api.acceptRefund(request);

        ArgumentCaptor<InstoreChannelCommandFacade.AcceptRefundCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.AcceptRefundCommand.class);
        verify(facade).acceptRefund(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(captor.getValue().channelCode()).isEqualTo("RT_MART");
        assertThat(captor.getValue().channelRefundNo()).isEqualTo("R9");
        assertThat(captor.getValue().channelRefundAmount()).isEqualTo(300L);
        assertThat(result.getData().getRefundStatus()).isEqualTo((int) InstoreRefundStatus.QUEUED.getCode());
        assertThat(result.getData().getClaimedForDispatch()).isFalse();
    }

    @Test
    void queryRefundStatusMapsRefundView() {
        when(contextAccessor.requireChannelContext()).thenReturn(new ChannelContext(101L, "RT_MART"));
        when(facade.queryAndConvergeRefund(any())).thenReturn(new InstoreChannelCommandFacade.RefundStatusView(
                "IRC_9", "R9", "CHANNEL_REFUND", InstoreRefundStatus.SUCCESS.getCode(), 300L, 300L, null, null, null));
        QueryRefundStatusReqDTO request = new QueryRefundStatusReqDTO();
        request.setChannelRefundNo("R9");

        CommonResult<RefundStatusRespDTO> result = api.queryRefundStatus(request);

        ArgumentCaptor<InstoreChannelCommandFacade.QueryRefundCommand> captor =
                ArgumentCaptor.forClass(InstoreChannelCommandFacade.QueryRefundCommand.class);
        verify(facade).queryAndConvergeRefund(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(result.getData().getStatus()).isEqualTo((int) InstoreRefundStatus.SUCCESS.getCode());
        assertThat(result.getData().getBuyerActualRefundAmount()).isEqualTo(300L);
    }

    @Test
    void rejectsAllEndpointsWhenChannelContextMissing() {
        when(contextAccessor.requireChannelContext()).thenThrow(new ServiceException(BUYER_AUTH_INVALID));

        assertThatThrownBy(() -> api.createPaymentOrder(new CreatePaymentOrderReqDTO()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertThatThrownBy(() -> api.queryPaymentStatus(new QueryPaymentStatusReqDTO()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertThatThrownBy(() -> api.cancelPaymentFlow(new CancelPaymentFlowReqDTO()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertThatThrownBy(() -> api.acceptRefund(new AcceptRefundReqDTO()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertThatThrownBy(() -> api.queryRefundStatus(new QueryRefundStatusReqDTO()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
        verifyNoInteractions(facade);
    }

    /** 入口与出口日志不得包含 userCode 全值与 cashierUrl。 */
    private void assertLogsRedacted() {
        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list)
                .allSatisfy(event -> {
                    String message = event.getFormattedMessage();
                    assertThat(message).doesNotContain("opaque-user");
                    assertThat(message).doesNotContain("https://cashier/pay");
                });
    }
}
