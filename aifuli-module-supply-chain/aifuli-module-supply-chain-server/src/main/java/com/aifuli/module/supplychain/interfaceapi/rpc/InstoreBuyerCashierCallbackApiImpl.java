package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.InstoreBuyerCashierCallbackApi;
import com.aifuli.module.supplychain.api.instore.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.ReportRefundResultReqDTO;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerCashierCallbackFacade;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 到店购收银台回调 RPC 实现（组合部署本地 @Primary；拆分部署由 Feign 走 ApiImpl）。
 *
 * <p>信任域为 SaaS 收银台 Principal：projectId 只来自可信 ProjectContext，
 * 归属按 outTradeNo/refundCaseNo 加载订单固化数据，回调参数不得改变归属。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#3-interface-adapter-层}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class InstoreBuyerCashierCallbackApiImpl implements InstoreBuyerCashierCallbackApi {

    private final ProjectCallContextAccessor contextAccessor;
    private final InstoreBuyerCashierCallbackFacade facade;

    /** 支付成功回调入口：ProjectContext 取身份后委托收敛 Facade。 */
    @Override
    public CommonResult<Void> confirmPayment(ConfirmPaymentReqDTO request) {
        ProjectCallContextAccessor.ProjectContext context = contextAccessor.requireProjectContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] confirmPayment 入口 projectId={}, outTradeNo={}, actualAmountCny={}, traceId={}",
                context.projectId(), request.getOutTradeNo(), request.getActualAmountCny(), traceId);
        facade.confirmPayment(new InstoreBuyerCashierCallbackFacade.PaymentCallbackCommand(
                context.projectId(), request.getOutTradeNo(), request.getActualAmountCny(),
                request.getCashierOrderNo(), request.getCashierTransactionNo(), traceId));
        log.info("[INSTORE][rpc] confirmPayment 出口 projectId={}, outTradeNo={}",
                context.projectId(), request.getOutTradeNo());
        return success(null);
    }

    /** 退款成功回调入口：ProjectContext 取身份后委托收敛 Facade。 */
    @Override
    public CommonResult<Void> reportRefundResult(ReportRefundResultReqDTO request) {
        ProjectCallContextAccessor.ProjectContext context = contextAccessor.requireProjectContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] reportRefundResult 入口 projectId={}, refundCaseNo={}, actualAmountCny={}, traceId={}",
                context.projectId(), request.getRefundCaseNo(), request.getActualAmountCny(), traceId);
        facade.confirmRefund(new InstoreBuyerCashierCallbackFacade.RefundCallbackCommand(
                context.projectId(), request.getRefundCaseNo(), request.getActualAmountCny(),
                request.getCashierRefundReference(), traceId));
        log.info("[INSTORE][rpc] reportRefundResult 出口 projectId={}, refundCaseNo={}",
                context.projectId(), request.getRefundCaseNo());
        return success(null);
    }

    private static String traceIdOrGenerate(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return traceId;
    }
}
