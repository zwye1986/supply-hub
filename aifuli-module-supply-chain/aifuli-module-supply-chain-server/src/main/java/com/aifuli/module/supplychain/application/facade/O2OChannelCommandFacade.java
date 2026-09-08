package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.api.o2o.dto.AcceptRefundReqDTO;
import com.aifuli.module.supplychain.api.o2o.dto.PaymentPreparationTicketDTO;
import com.aifuli.module.supplychain.api.o2o.dto.PreparePaymentReqDTO;
import com.aifuli.module.supplychain.border.o2o.application.O2OChannelLifecycle;
import com.aifuli.module.supplychain.border.o2o.application.O2OChannelLifecycle.PreparePaymentCommand;
import com.aifuli.module.supplychain.border.o2o.application.O2OChannelLifecycle.PreparedPayment;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * O2O 渠道命令门面：接收渠道服务的支付预下单与退款命令，跨事务编排 Lifecycle 与收银台 Port。
 *
 * <p>支付预下单固定四段：建单冻结→CAS 抢占→事务外调收银台→记录结果。收银台调用位于数据库事务外，
 * 超时保持原 outTradeNo/invocationId，由 PersistentTimer 查单收敛。详见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class O2OChannelCommandFacade {

    private final O2OChannelLifecycle lifecycle;
    private final BuyerCashierPort buyerCashierPort;
    private final BusinessNoGenerator businessNoGenerator;

    /**
     * 渠道支付预下单：建单冻结后抢占 PREORDER，事务外拉起采购商收银台并回写结果。
     *
     * <p>幂等回放或抢占失败均不调用收银台。第三方收银台调用记录请求参数、响应与耗时。</p>
     */
    public PaymentPreparationTicketDTO preparePayment(PreparePaymentReqDTO request) {
        String traceId = businessNoGenerator.next(BusinessNoPrefixes.REQ);
        log.info("[O2O][facade] preparePayment 入口 channelCode={}, transactionId={}, channelUserId={}, payAmount={}, merchantId={}, storeId={}, traceId={}",
                request.getChannelCode(), request.getTransactionId(), request.getChannelUserId(),
                request.getPayAmount(), request.getExternalMerchantId(), request.getExternalStoreId(), traceId);
        PreparePaymentCommand command = new PreparePaymentCommand(
                request.getChannelCode(), request.getTransactionId(), request.getChannelUserId(),
                request.getPayAmount(), request.getExternalMerchantId(), request.getExternalStoreId(),
                request.getExternalStoreName(), request.getSubject(), traceId);

        PreparedPayment prepared = lifecycle.createPaymentPreparation(command);
        if (prepared.idempotentReplay()) {
            log.info("[O2O][facade] preparePayment 幂等回放 outTradeNo={}, invocationId={}",
                    prepared.outTradeNo(), prepared.invocationId());
            return toTicket(prepared);
        }
        O2OChannelLifecycle.ClaimResult claim = lifecycle.claimPaymentPreparation(
                prepared.invocationId(), prepared.operationVersion());
        if (!claim.claimed()) {
            log.info("[O2O][facade] preparePayment 抢占失败，跳过收银台 outTradeNo={}, invocationId={}",
                    prepared.outTradeNo(), prepared.invocationId());
            return toTicket(prepared);
        }
        BuyerCashierPort.CreateCashierOrderResult cashierResult;
        long startMs = System.currentTimeMillis();
        try {
            log.info("[O2O][cashier] createCashierOrder 请求 invocationId={}, outTradeNo={}, amount={}",
                    prepared.invocationId(), prepared.outTradeNo(), prepared.payAmount());
            cashierResult = buyerCashierPort.createLegacyCashierOrder(
                    new BuyerCashierPort.LegacyCreateCashierOrderCommand(
                            prepared.invocationId(), prepared.outTradeNo(), prepared.payAmount(),
                            request.getSubject(), OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30)));
            log.info("[O2O][cashier] createCashierOrder 响应 status={}, cashierOrderNo={}, launchDescriptor={}, cost={}ms",
                    cashierResult.status(), cashierResult.cashierOrderNo(),
                    cashierResult.launchDescriptor(), System.currentTimeMillis() - startMs);
        } catch (RuntimeException ex) {
            log.warn("[O2O][cashier] createCashierOrder 失败, keep outTradeNo={}, invocationId={}, cost={}ms",
                    prepared.outTradeNo(), prepared.invocationId(), System.currentTimeMillis() - startMs, ex);
            return toTicket(prepared);
        }
        lifecycle.recordCashierOrder(prepared.invocationId(), claim.operationVersion(), cashierResult);
        PreparedPayment updated = new PreparedPayment(prepared.orderId(), prepared.orderNo(),
                prepared.detailId(), prepared.channelDetailNo(), prepared.outTradeNo(),
                prepared.invocationId(), claim.operationVersion(), prepared.payAmount(),
                prepared.reservationNo(), false);
        PaymentPreparationTicketDTO ticket = toTicket(updated);
        ticket.setLaunchDescriptor(cashierResult.launchDescriptor());
        log.info("[O2O][facade] preparePayment 完成 orderNo={}, outTradeNo={}, invocationId={}",
                ticket.getOrderNo(), ticket.getOutTradeNo(), ticket.getInvocationId());
        return ticket;
    }

    /**
     * 渠道退款受理：按 (channelCode, channelRefundNo) 全局幂等创建退款 Case，返回中台 refundCaseNo。
     */
    public String acceptRefund(AcceptRefundReqDTO request) {
        String traceId = businessNoGenerator.next(BusinessNoPrefixes.REQ);
        log.info("[O2O][facade] acceptRefund 入口 channelCode={}, channelRefundNo={}, transactionId={}, refundType={}, amount={}, traceId={}",
                request.getChannelCode(), request.getChannelRefundNo(), request.getTransactionId(),
                request.getRefundType(), request.getChannelRefundAmount(), traceId);
        O2OChannelLifecycle.AcceptRefundCommand command = new O2OChannelLifecycle.AcceptRefundCommand(
                request.getChannelCode(), request.getChannelRefundNo(), request.getTransactionId(),
                request.getRefundType(), request.getChannelRefundAmount(), traceId);
        String refundCaseNo = lifecycle.acceptRefund(command);
        log.info("[O2O][facade] acceptRefund 完成 refundCaseNo={}", refundCaseNo);
        return refundCaseNo;
    }

    private static PaymentPreparationTicketDTO toTicket(PreparedPayment prepared) {
        PaymentPreparationTicketDTO ticket = new PaymentPreparationTicketDTO();
        ticket.setOrderNo(prepared.orderNo());
        ticket.setOutTradeNo(prepared.outTradeNo());
        ticket.setInvocationId(prepared.invocationId());
        ticket.setExpectedVersion(prepared.operationVersion());
        return ticket;
    }
}
