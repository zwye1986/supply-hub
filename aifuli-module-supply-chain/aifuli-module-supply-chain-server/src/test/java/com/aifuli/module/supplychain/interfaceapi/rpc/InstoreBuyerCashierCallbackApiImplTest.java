package com.aifuli.module.supplychain.interfaceapi.rpc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerCashierCallbackFacade;
import com.aifuli.module.supplychain.api.instore.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.ReportRefundResultReqDTO;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.ProjectContext;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 到店购收银台回调 RPC 实现单测：ProjectContext 是唯一身份来源，回调参数不改归属，
 * 入口/出口日志不含收银台第三方流水引用。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#3-interface-adapter-层}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreBuyerCashierCallbackApiImplTest {

    @Mock private ProjectCallContextAccessor contextAccessor;
    @Mock private InstoreBuyerCashierCallbackFacade facade;

    private InstoreBuyerCashierCallbackApiImpl api;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger implLogger;

    @BeforeEach
    void setUp() {
        api = new InstoreBuyerCashierCallbackApiImpl(contextAccessor, facade);
        implLogger = (Logger) LoggerFactory.getLogger(InstoreBuyerCashierCallbackApiImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        implLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        implLogger.detachAppender(logAppender);
    }

    @Test
    void confirmPaymentMapsCommandFromProjectContext() {
        when(contextAccessor.requireProjectContext()).thenReturn(new ProjectContext(101L));
        ConfirmPaymentReqDTO request = new ConfirmPaymentReqDTO();
        request.setOutTradeNo("IPF_OTN");
        request.setActualAmountCny(10_000L);
        request.setCashierOrderNo("CASHIER_1");
        request.setCashierTransactionNo("TXN_1");
        request.setTraceId("trace-cb");

        api.confirmPayment(request);

        ArgumentCaptor<InstoreBuyerCashierCallbackFacade.PaymentCallbackCommand> captor =
                ArgumentCaptor.forClass(InstoreBuyerCashierCallbackFacade.PaymentCallbackCommand.class);
        verify(facade).confirmPayment(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(captor.getValue().outTradeNo()).isEqualTo("IPF_OTN");
        assertThat(captor.getValue().actualAmountCny()).isEqualTo(10_000L);
        assertThat(captor.getValue().cashierOrderNo()).isEqualTo("CASHIER_1");
        assertThat(captor.getValue().cashierTransactionNo()).isEqualTo("TXN_1");
        assertThat(captor.getValue().traceId()).isEqualTo("trace-cb");
        assertLogsRedacted();
    }

    @Test
    void reportRefundResultMapsCommandFromProjectContext() {
        when(contextAccessor.requireProjectContext()).thenReturn(new ProjectContext(101L));
        ReportRefundResultReqDTO request = new ReportRefundResultReqDTO();
        request.setRefundCaseNo("IRC_9");
        request.setActualAmountCny(300L);
        request.setCashierRefundReference("CRF_1");

        api.reportRefundResult(request);

        ArgumentCaptor<InstoreBuyerCashierCallbackFacade.RefundCallbackCommand> captor =
                ArgumentCaptor.forClass(InstoreBuyerCashierCallbackFacade.RefundCallbackCommand.class);
        verify(facade).confirmRefund(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(101L);
        assertThat(captor.getValue().refundCaseNo()).isEqualTo("IRC_9");
        assertThat(captor.getValue().actualAmountCny()).isEqualTo(300L);
        assertThat(captor.getValue().cashierRefundReference()).isEqualTo("CRF_1");
        assertThat(captor.getValue().traceId()).isNotBlank();
        assertLogsRedacted();
    }

    @Test
    void rejectsCallbacksWhenProjectContextMissing() {
        when(contextAccessor.requireProjectContext()).thenThrow(new ServiceException(BUYER_AUTH_INVALID));

        assertThatThrownBy(() -> api.confirmPayment(new ConfirmPaymentReqDTO()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertThatThrownBy(() -> api.reportRefundResult(new ReportRefundResultReqDTO()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
        verifyNoInteractions(facade);
    }

    /** 回调 Adapter 日志只记录业务键，不含收银台第三方流水引用。 */
    private void assertLogsRedacted() {
        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list)
                .allSatisfy(event -> {
                    String message = event.getFormattedMessage();
                    assertThat(message).doesNotContain("TXN_1");
                    assertThat(message).doesNotContain("CRF_1");
                });
    }
}
