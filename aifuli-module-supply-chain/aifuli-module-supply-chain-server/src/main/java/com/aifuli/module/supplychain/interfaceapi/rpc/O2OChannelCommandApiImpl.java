package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.o2o.O2OChannelCommandApi;
import com.aifuli.module.supplychain.api.o2o.dto.AcceptRefundReqDTO;
import com.aifuli.module.supplychain.api.o2o.dto.PaymentPreparationTicketDTO;
import com.aifuli.module.supplychain.api.o2o.dto.PreparePaymentReqDTO;
import com.aifuli.module.supplychain.application.facade.O2OChannelCommandFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * O2O 渠道命令 RPC 实现：由淘宝闪购渠道服务调用。
 *
 * <p>信任域为渠道 Principal；本类只做入参日志与委托 Facade，不承载业务编排。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class O2OChannelCommandApiImpl implements O2OChannelCommandApi {

    private final O2OChannelCommandFacade facade;

    /**
     * 渠道支付预下单入口：记录关键业务键后委托 Facade。
     */
    @Override
    public CommonResult<PaymentPreparationTicketDTO> preparePayment(PreparePaymentReqDTO request) {
        log.info("[O2O][rpc] preparePayment 入口 channelCode={}, transactionId={}, channelUserId={}, payAmount={}",
                request.getChannelCode(), request.getTransactionId(), request.getChannelUserId(), request.getPayAmount());
        PaymentPreparationTicketDTO ticket = facade.preparePayment(request);
        log.info("[O2O][rpc] preparePayment 出口 orderNo={}, outTradeNo={}, invocationId={}",
                ticket.getOrderNo(), ticket.getOutTradeNo(), ticket.getInvocationId());
        return success(ticket);
    }

    /**
     * 渠道退款受理入口：记录渠道退款号后委托 Facade，返回中台 refundCaseNo。
     */
    @Override
    public CommonResult<String> acceptRefund(AcceptRefundReqDTO request) {
        log.info("[O2O][rpc] acceptRefund 入口 channelCode={}, channelRefundNo={}, transactionId={}, amount={}",
                request.getChannelCode(), request.getChannelRefundNo(), request.getTransactionId(),
                request.getChannelRefundAmount());
        String refundCaseNo = facade.acceptRefund(request);
        log.info("[O2O][rpc] acceptRefund 出口 refundCaseNo={}", refundCaseNo);
        return success(refundCaseNo);
    }
}
