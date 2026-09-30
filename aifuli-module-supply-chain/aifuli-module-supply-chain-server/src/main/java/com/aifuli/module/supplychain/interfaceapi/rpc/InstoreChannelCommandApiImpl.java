package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.InstoreChannelCommandApi;
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
import com.aifuli.module.supplychain.application.facade.InstoreChannelCommandFacade;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 到店购支付/退款命令 RPC 实现（组合部署本地 @Primary；拆分部署由 Feign 走 ApiImpl）。
 *
 * <p>只做三件事：取可信渠道上下文、出入口日志、Wire DTO 与 Facade Command 映射；
 * 身份（projectId/channelCode）只能来自可信上下文，异常由全局处理转 CommonResult。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#3-interface-adapter-层}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class InstoreChannelCommandApiImpl implements InstoreChannelCommandApi {

    private final ProjectCallContextAccessor contextAccessor;
    private final InstoreChannelCommandFacade facade;

    /** 到店购建单入口：取可信渠道上下文后映射 Wire 命令委托 Facade。 */
    @Override
    public CommonResult<PaymentTicketRespDTO> createPaymentOrder(CreatePaymentOrderReqDTO request) {
        ProjectCallContextAccessor.ChannelContext context = contextAccessor.requireChannelContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] createPaymentOrder 入口 projectId={}, channelCode={}, channelOrderId={}, tradeId={}, payAmount={}, traceId={}",
                context.projectId(), context.channelCode(), request.getChannelOrderId(),
                request.getTradeId(), request.getPayAmount(), traceId);
        InstoreChannelCommandFacade.PaymentTicket ticket = facade.createPaymentOrder(
                new InstoreChannelCommandFacade.WireCreatePaymentCommand(
                        context.projectId(), context.channelCode(), request.getChannelOrderId(),
                        request.getTradeId(), request.getUserCode(), request.getPayAmount(),
                        request.getOrderType(), request.getTotalAmount(), request.getCompanyCode(),
                        request.getExternalMerchantId(), request.getShopCode(), request.getShopName(),
                        request.getSubject(), traceId));
        PaymentTicketRespDTO response = new PaymentTicketRespDTO();
        response.setOrderNo(ticket.orderNo());
        response.setOutTradeNo(ticket.outTradeNo());
        response.setCashierUrl(ticket.cashierUrl());
        response.setPayStatus((int) ticket.payStatus());
        log.info("[INSTORE][rpc] createPaymentOrder 出口 projectId={}, channelCode={}, orderNo={}, outTradeNo={}, payStatus={}, hasCashierUrl={}",
                context.projectId(), context.channelCode(), ticket.orderNo(), ticket.outTradeNo(),
                ticket.payStatus(), Objects.nonNull(ticket.cashierUrl()));
        return success(response);
    }

    /** 支付状态查询入口：收敛后映射状态视图返回渠道。 */
    @Override
    public CommonResult<PaymentStatusRespDTO> queryPaymentStatus(QueryPaymentStatusReqDTO request) {
        ProjectCallContextAccessor.ChannelContext context = contextAccessor.requireChannelContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] queryPaymentStatus 入口 projectId={}, channelCode={}, tradeId={}, traceId={}",
                context.projectId(), context.channelCode(), request.getTradeId(), traceId);
        InstoreChannelCommandFacade.PaymentStatusView view = facade.queryAndConvergePayment(
                new InstoreChannelCommandFacade.QueryPaymentCommand(
                        context.projectId(), context.channelCode(), request.getTradeId(), traceId));
        PaymentStatusRespDTO response = new PaymentStatusRespDTO();
        response.setPaymentFlowNo(view.paymentFlowNo());
        response.setOutTradeNo(view.outTradeNo());
        response.setTradeId(view.tradeId());
        response.setPayStatus((int) view.payStatus());
        response.setPayAmount(view.payAmount());
        response.setBuyerPaidAmount(view.buyerPaidAmount());
        response.setCashierReference(view.cashierReference());
        response.setExternalTransactionId(view.externalTransactionId());
        log.info("[INSTORE][rpc] queryPaymentStatus 出口 projectId={}, channelCode={}, tradeId={}, payStatus={}, buyerPaidAmount={}",
                context.projectId(), context.channelCode(), request.getTradeId(),
                view.payStatus(), view.buyerPaidAmount());
        return success(response);
    }

    /** 支付撤销入口：映射撤销命令，转退款结果原样返回。 */
    @Override
    public CommonResult<CancelPaymentFlowRespDTO> cancelPaymentFlow(CancelPaymentFlowReqDTO request) {
        ProjectCallContextAccessor.ChannelContext context = contextAccessor.requireChannelContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] cancelPaymentFlow 入口 projectId={}, channelCode={}, tradeId={}, traceId={}",
                context.projectId(), context.channelCode(), request.getTradeId(), traceId);
        InstoreChannelCommandFacade.CancelPaymentTicket ticket = facade.cancelPaymentFlow(
                new InstoreChannelCommandFacade.CancelPaymentCommand(
                        context.projectId(), context.channelCode(), request.getTradeId(), traceId));
        CancelPaymentFlowRespDTO response = new CancelPaymentFlowRespDTO();
        response.setOutTradeNo(ticket.outTradeNo());
        response.setRefundCaseNo(ticket.refundCaseNo());
        response.setConvertedToRefund(ticket.convertedToRefund());
        response.setClaimedForDispatch(ticket.claimedForDispatch());
        log.info("[INSTORE][rpc] cancelPaymentFlow 出口 projectId={}, channelCode={}, tradeId={}, convertedToRefund={}, claimedForDispatch={}",
                context.projectId(), context.channelCode(), request.getTradeId(),
                ticket.convertedToRefund(), ticket.claimedForDispatch());
        return success(response);
    }

    /** 退款受理入口：映射受理命令，返回受理与派发状态。 */
    @Override
    public CommonResult<RefundAcceptanceRespDTO> acceptRefund(AcceptRefundReqDTO request) {
        ProjectCallContextAccessor.ChannelContext context = contextAccessor.requireChannelContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] acceptRefund 入口 projectId={}, channelCode={}, channelRefundNo={}, tradeId={}, channelRefundAmount={}, traceId={}",
                context.projectId(), context.channelCode(), request.getChannelRefundNo(),
                request.getTradeId(), request.getChannelRefundAmount(), traceId);
        InstoreChannelCommandFacade.RefundAcceptance acceptance = facade.acceptRefund(
                new InstoreChannelCommandFacade.AcceptRefundCommand(
                        context.projectId(), context.channelCode(), request.getChannelRefundNo(), request.getTradeId(),
                        request.getRefundType(), request.getChannelRefundAmount(), traceId));
        RefundAcceptanceRespDTO response = new RefundAcceptanceRespDTO();
        response.setRefundCaseNo(acceptance.refundCaseNo());
        response.setRefundStatus((int) acceptance.refundStatus());
        response.setClaimedForDispatch(acceptance.claimedForDispatch());
        log.info("[INSTORE][rpc] acceptRefund 出口 projectId={}, channelCode={}, refundCaseNo={}, refundStatus={}, claimedForDispatch={}",
                context.projectId(), context.channelCode(), acceptance.refundCaseNo(),
                acceptance.refundStatus(), acceptance.claimedForDispatch());
        return success(response);
    }

    /** 退款状态查询入口：收敛后映射退款视图返回渠道。 */
    @Override
    public CommonResult<RefundStatusRespDTO> queryRefundStatus(QueryRefundStatusReqDTO request) {
        ProjectCallContextAccessor.ChannelContext context = contextAccessor.requireChannelContext();
        String traceId = traceIdOrGenerate(request.getTraceId());
        log.info("[INSTORE][rpc] queryRefundStatus 入口 projectId={}, channelCode={}, channelRefundNo={}, traceId={}",
                context.projectId(), context.channelCode(), request.getChannelRefundNo(), traceId);
        InstoreChannelCommandFacade.RefundStatusView view = facade.queryAndConvergeRefund(
                new InstoreChannelCommandFacade.QueryRefundCommand(
                        context.projectId(), context.channelCode(),
                        request.getChannelRefundNo(), traceId));
        RefundStatusRespDTO response = new RefundStatusRespDTO();
        response.setRefundCaseNo(view.refundCaseNo());
        response.setChannelRefundNo(view.channelRefundNo());
        response.setRefundType(view.refundType());
        response.setStatus((int) view.status());
        response.setChannelRefundAmount(view.channelRefundAmount());
        response.setBuyerActualRefundAmount(view.buyerActualRefundAmount());
        log.info("[INSTORE][rpc] queryRefundStatus 出口 projectId={}, channelCode={}, refundCaseNo={}, status={}, buyerActualRefundAmount={}",
                context.projectId(), context.channelCode(), view.refundCaseNo(),
                view.status(), view.buyerActualRefundAmount());
        return success(response);
    }

    private static String traceIdOrGenerate(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return traceId;
    }
}
