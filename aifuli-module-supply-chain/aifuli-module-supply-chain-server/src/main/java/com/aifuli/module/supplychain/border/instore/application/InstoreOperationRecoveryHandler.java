package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreOperationDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreOperationMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOperationStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOperationType;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 到店购外部操作恢复入口：PersistentTimer 超时唤醒或节点宕机后，用原 outTradeNo/trade_id/refundCaseNo
 * + expectedVersion 重新进入 {@link InstoreChannelLifecycle} 条件更新方法，禁止生成新支付单号或新退款单号。
 *
 * <p>本类仅承载恢复决策与重入编排；具体的资金释放、重路由或退款返还仍由 Lifecycle 在事务内完成。
 * 退款操作（BUYER_REFUND/REFUND_QUERY）按数据库固化的原 refundCaseNo 进入
 * {@link InstoreRefundConvergenceService} 权威查询收敛；Case 已终态时跳过外呼。
 * Timer 注册与扫描由 {@code infrastructure/timer} 模块统一管理，本类不直接耦合 Timer 注册基础设施
 * （设计详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#4-中台侧场景详情模块}
 * 与 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstoreOperationRecoveryHandler {

    private final InstoreOperationMapper operationMapper;
    private final InstorePaymentFlowMapper paymentFlowMapper;
    private final InstoreRefundCaseMapper refundCaseMapper;
    private final InstorePaymentConvergenceService convergenceService;
    private final InstoreRefundConvergenceService refundConvergenceService;

    /**
     * 恢复一个待执行或结果未知的操作。
     *
     * <p>按操作类型选择恢复路径：
     * <ul>
     *   <li>PREORDER/PAYMENT_QUERY：按原 outTradeNo 调统一收敛服务权威查 SaaS 收银台。</li>
     *   <li>结果未知的取消：按原 trade_id 输出决策，待取消收银台切片接入同一模式。</li>
     *   <li>BUYER_REFUND/REFUND_QUERY：按数据库固化的原 refundCaseNo 调退款统一收敛服务；
     *       Case 已终态（含 MANUAL）时跳过外呼，避免重复查单与日志风暴。</li>
     * </ul>
     * 所有路径禁止生成新单号；金额不一致等不可恢复错误由收敛服务以稳定错误码上抛，交由人工处理。</p>
     *
     * @return 恢复决策描述，供 Timer 模块记录与后续 next_recover_at 调度
     */
    public RecoveryDecision recover(String operationNo) {
        log.info("[INSTORE][recovery] recover 入口 operationNo={}", operationNo);
        InstoreOperationDO operation = operationMapper.selectByOperationNo(operationNo);
        if (operation == null) {
            throw new IllegalStateException("Instore operation not found: " + operationNo);
        }
        if (operation.getStatus() == InstoreOperationStatus.SUCCESS.getCode()
                || operation.getStatus() == InstoreOperationStatus.FAILED.getCode()) {
            log.info("[INSTORE][recovery] 操作已终态，跳过恢复 operationNo={}, status={}",
                    operationNo, operation.getStatus());
            return new RecoveryDecision(operationNo, operation.getOperationType(),
                    "ALREADY_TERMINAL", operation.getVersion());
        }
        InstoreOperationType type = InstoreOperationType.valueOf(operation.getOperationType());
        String action = switch (type) {
            case PREORDER, PAYMENT_QUERY -> {
                InstorePaymentFlowDO flow = requirePaymentFlow(operation);
                convergenceService.queryAndConvergePaymentByOutTradeNo(
                        flow.getOutTradeNo(), "TIMER_RECOVERY:" + operationNo);
                yield "QUERY_PAYMENT:" + flow.getOutTradeNo();
            }
            case CANCEL_PAY -> "QUERY_CANCEL:" + requirePaymentFlow(operation).getTradeId();
            case BUYER_REFUND, REFUND_QUERY -> recoverRefundOperation(operationNo, operation);
        };
        log.info("[INSTORE][recovery] 恢复决策 operationNo={}, type={}, action={}",
                operationNo, type, action);
        return new RecoveryDecision(operationNo, operation.getOperationType(), action, operation.getVersion());
    }

    /**
     * 退款操作恢复：只接受数据库固化的 refundCaseId，按原 refundCaseNo 权威查询收敛。
     *
     * <p>Case 已终态时直接跳过（回调或前次查询已收敛），不外呼收银台；
     * 非终态时交由 {@link InstoreRefundConvergenceService} 统一决策是否外呼与后续派发。</p>
     */
    private String recoverRefundOperation(String operationNo, InstoreOperationDO operation) {
        if (operation.getRefundCaseId() == null) {
            throw new IllegalStateException("Refund operation missing refundCaseId: " + operationNo);
        }
        InstoreRefundCaseDO refundCase = refundCaseMapper.selectById(operation.getRefundCaseId());
        if (refundCase == null) {
            throw new IllegalStateException("Refund case disappeared during recovery: " + operationNo);
        }
        if (isTerminalRefundStatus(refundCase.getStatus())) {
            log.info("[INSTORE][recovery] 退款 Case 已终态，跳过权威查询 operationNo={}, refundCaseNo={}, status={}",
                    operationNo, refundCase.getRefundCaseNo(), refundCase.getStatus());
            return "ALREADY_CONVERGED:" + refundCase.getRefundCaseNo();
        }
        refundConvergenceService.queryAndConvergeRefundByRefundCaseNo(
                refundCase.getRefundCaseNo(), "TIMER_RECOVERY:" + operationNo);
        return "QUERY_REFUND:" + refundCase.getRefundCaseNo();
    }

    private InstorePaymentFlowDO requirePaymentFlow(InstoreOperationDO operation) {
        InstorePaymentFlowDO flow = paymentFlowMapper.selectById(operation.getPaymentFlowId());
        if (flow == null) {
            throw new IllegalStateException("Payment flow disappeared during recovery: " + operation.getOperationNo());
        }
        return flow;
    }

    /** 退款 Case 终态：成功/失败/已完成/人工处理；与收敛服务的终态判定保持一致。 */
    private static boolean isTerminalRefundStatus(Short status) {
        return status != null
                && (status == InstoreRefundStatus.SUCCESS.getCode()
                || status == InstoreRefundStatus.FAILED.getCode()
                || status == InstoreRefundStatus.COMPLETED.getCode()
                || status == InstoreRefundStatus.MANUAL.getCode());
    }

    /** 恢复决策：操作号、操作类型、恢复动作描述与操作版本号。 */
    public record RecoveryDecision(String operationNo, String operationType,
                                    String action, long operationVersion) {
    }
}
