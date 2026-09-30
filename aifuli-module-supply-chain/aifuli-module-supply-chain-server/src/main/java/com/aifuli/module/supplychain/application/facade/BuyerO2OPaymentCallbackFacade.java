package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.api.o2o.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.o2o.dto.ReportRefundResultReqDTO;
import com.aifuli.module.supplychain.border.o2o.application.O2OChannelLifecycle;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 采购商收银台回调门面：按 outTradeNo / middleRefundNo 加载已固化归属后委托 Lifecycle 收敛。
 *
 * <p>回调不依赖回调参数重新传递 BuyerUser，必须先按业务键加载订单中已固化的 Buyer 与 BuyerUser，
 * 再核对已认证 Principal。详见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerO2OPaymentCallbackFacade {

    private final O2OChannelLifecycle lifecycle;
    private final BusinessNoGenerator businessNoGenerator;

    /**
     * 确认采购商支付：按 outTradeNo 进入 Lifecycle 确认资金预留并写渠道支付通知 Outbox。
     */
    public void confirmPayment(ConfirmPaymentReqDTO request) {
        String traceId = businessNoGenerator.next(BusinessNoPrefixes.REQ);
        log.info("[O2O][facade] confirmPayment 入口 outTradeNo={}, buyerPaidAmount={}, cashierReference={}, traceId={}",
                request.getOutTradeNo(), request.getBuyerPaidAmount(), request.getCashierReference(), traceId);
        lifecycle.confirmBuyerPayment(request.getOutTradeNo(), request.getBuyerPaidAmount(),
                request.getCashierReference(), traceId);
        log.info("[O2O][facade] confirmPayment 完成 outTradeNo={}, traceId={}", request.getOutTradeNo(), traceId);
    }

    /**
     * 上报采购商退款结果：按 middleRefundNo 进入 Lifecycle 写渠道退款通知 Outbox。
     */
    public void reportRefundResult(ReportRefundResultReqDTO request) {
        String traceId = businessNoGenerator.next(BusinessNoPrefixes.REQ);
        log.info("[O2O][facade] reportRefundResult 入口 middleRefundNo={}, refundStatus={}, actualAmount={}, cashierRefundReference={}, traceId={}",
                request.getMiddleRefundNo(), request.getRefundStatus(), request.getBuyerActualRefundAmount(),
                request.getCashierRefundReference(), traceId);
        lifecycle.applyBuyerRefundResult(request.getMiddleRefundNo(), request.getRefundStatus(),
                request.getBuyerActualRefundAmount(), request.getCashierRefundReference(), traceId);
        log.info("[O2O][facade] reportRefundResult 完成 middleRefundNo={}, traceId={}",
                request.getMiddleRefundNo(), traceId);
    }
}
