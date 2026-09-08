package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.border.instore.application.InstorePaymentConvergenceService;
import com.aifuli.module.supplychain.border.instore.application.InstoreRefundConvergenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * SaaS 收银台回调 Facade 单测：支付与退款回调均以可信 projectId 转发统一收敛服务。
 *
 * <p>详见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.1-采购商侧业务接口}
 * 与 {@code docs/superpowers/plans/2026-08-23-instore-refund-serialization.md}（Task 7）。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreBuyerCashierCallbackFacadeTest {

    @Mock private InstorePaymentConvergenceService convergenceService;
    @Mock private InstoreRefundConvergenceService refundConvergenceService;
    @InjectMocks private InstoreBuyerCashierCallbackFacade facade;

    @Test
    void confirmPaymentDelegatesWithTrustedProjectScope() {
        facade.confirmPayment(new InstoreBuyerCashierCallbackFacade.PaymentCallbackCommand(
                1L, "OT1", 10_000L, "CASHIER_1", "TXN_1", "trace-1"));

        verify(convergenceService).confirmPayment(1L, "OT1", 10_000L, "CASHIER_1", "TXN_1", "trace-1");
    }

    @Test
    void confirmRefundDelegatesWithTrustedProjectScope() {
        facade.confirmRefund(new InstoreBuyerCashierCallbackFacade.RefundCallbackCommand(
                1L, "IRC_9", 300L, "CRF_1", "trace-1"));

        verify(refundConvergenceService).confirmRefund(1L, "IRC_9", 300L, "CRF_1", "trace-1");
    }
}
