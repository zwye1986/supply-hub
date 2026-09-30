package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.o2o.BuyerO2OPaymentCallbackApi;
import com.aifuli.module.supplychain.api.o2o.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.o2o.dto.ReportRefundResultReqDTO;
import com.aifuli.module.supplychain.application.facade.BuyerO2OPaymentCallbackFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 采购商收银台回调 RPC 实现：由 SaaS 商城收银台调用。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class BuyerO2OPaymentCallbackApiImpl implements BuyerO2OPaymentCallbackApi {

    private final BuyerO2OPaymentCallbackFacade facade;

    /**
     * 收银台支付确认入口：记录 outTradeNo 与实付金额后委托 Facade。
     */
    @Override
    public CommonResult<Void> confirmPayment(ConfirmPaymentReqDTO request) {
        log.info("[O2O][rpc] confirmPayment 入口 outTradeNo={}, buyerPaidAmount={}, cashierReference={}",
                request.getOutTradeNo(), request.getBuyerPaidAmount(), request.getCashierReference());
        facade.confirmPayment(request);
        return success(null);
    }

    /**
     * 收银台退款结果上报入口：记录 middleRefundNo 与退款状态后委托 Facade。
     */
    @Override
    public CommonResult<Void> reportRefundResult(ReportRefundResultReqDTO request) {
        log.info("[O2O][rpc] reportRefundResult 入口 middleRefundNo={}, refundStatus={}, actualAmount={}",
                request.getMiddleRefundNo(), request.getRefundStatus(), request.getBuyerActualRefundAmount());
        facade.reportRefundResult(request);
        return success(null);
    }
}
