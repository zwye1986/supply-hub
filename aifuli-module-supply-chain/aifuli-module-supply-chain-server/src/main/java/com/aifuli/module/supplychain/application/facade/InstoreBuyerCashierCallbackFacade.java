package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.border.instore.application.InstorePaymentConvergenceService;
import com.aifuli.module.supplychain.border.instore.application.InstoreRefundConvergenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * SaaS 商城收银台支付/退款回调应用入口。
 *
 * <p>Adapter 必须先完成回调鉴权并提供可信 projectId；本类不接受 sourceSystem/externalTenantId，
 * 而是按 outTradeNo/refundCaseNo 加载订单固化归属后校验项目。回调成功直接收敛，不先发起权威查询。
 * 详见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.1-采购商侧业务接口}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstoreBuyerCashierCallbackFacade {

    private final InstorePaymentConvergenceService convergenceService;
    private final InstoreRefundConvergenceService refundConvergenceService;

    /** 支付成功回调；重复回调由 Lifecycle 的支付状态与 CAS 幂等处理。 */
    public void confirmPayment(PaymentCallbackCommand command) {
        Objects.requireNonNull(command, "command");
        log.info("[INSTORE][callback] confirmPayment 入口 projectId={}, outTradeNo={}, actualAmount={}, "
                        + "cashierOrderNo={}, traceId={}",
                command.projectId(), command.outTradeNo(), command.actualAmountCny(),
                command.cashierOrderNo(), command.traceId());
        convergenceService.confirmPayment(command.projectId(), command.outTradeNo(),
                command.actualAmountCny(), command.cashierOrderNo(),
                command.cashierTransactionNo(), command.traceId());
    }

    /** Adapter 内部命令；projectId 来自可信回调认证上下文，不属于回调 Wire DTO。 */
    public record PaymentCallbackCommand(Long projectId, String outTradeNo, long actualAmountCny,
                                         String cashierOrderNo, String cashierTransactionNo,
                                         String traceId) {
    }

    /**
     * 退款成功回调：Adapter 完成鉴权后提供可信 projectId；按原 refundCaseNo 校验归属后直接收敛，
     * 不先发起权威查询；金额不一致由收敛服务抛稳定错误码（Case 已在事务内转 MANUAL）。
     */
    public void confirmRefund(RefundCallbackCommand command) {
        Objects.requireNonNull(command, "command");
        log.info("[INSTORE][callback] confirmRefund 入口 projectId={}, refundCaseNo={}, actualAmount={}, "
                        + "cashierRefundReference={}, traceId={}",
                command.projectId(), command.refundCaseNo(), command.actualAmountCny(),
                command.cashierRefundReference(), command.traceId());
        refundConvergenceService.confirmRefund(command.projectId(), command.refundCaseNo(),
                command.actualAmountCny(), command.cashierRefundReference(), command.traceId());
    }

    /** Adapter 内部命令；projectId 来自可信回调认证上下文，不属于回调 Wire DTO。 */
    public record RefundCallbackCommand(Long projectId, String refundCaseNo, long actualAmountCny,
                                        String cashierRefundReference, String traceId) {
    }
}
