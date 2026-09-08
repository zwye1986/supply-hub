package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ORDER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_CASE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT;

/**
 * 到店购退款统一收敛服务：SaaS 回调优先，本地非终态时按原 refundCaseNo 权威查询兜底。
 *
 * <p>渠道查询（接口⑥）、PersistentTimer 恢复与派发外呼后的响应处理必须复用本服务；
 * 回调、查询与 Timer 并发时依靠 Lifecycle 的 Case 状态 CAS 幂等收敛。
 * 金额不一致时 Case 已在 Lifecycle 事务内转 MANUAL，本服务在事务提交后抛
 * {@code INSTORE_REFUND_AMOUNT_MISMATCH}（RetryDirective.MANUAL，禁止调用方自动重试）。
 * 网络调用不在本地事务中执行。详见
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 8 条
 * 与 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#51-saas-商城收银台编排} 第 4 条。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstoreRefundConvergenceService {

    private final InstoreRefundCaseMapper refundCaseMapper;
    private final InstorePaymentFlowMapper paymentFlowMapper;
    private final InstoreChannelOrderMapper orderMapper;
    private final BuyerIntegrationRouteResolver routeResolver;
    private final BuyerCashierPort cashierPort;
    private final InstoreChannelLifecycle lifecycle;

    /** 回调优先入口：校验已认证项目拥有该 refundCaseNo 后直接进入 Lifecycle 收敛，不先查询。 */
    public void confirmRefund(Long expectedProjectId, String refundCaseNo, Long actualRefundAmountCny,
                              String cashierRefundReference, String traceId) {
        log.info("[INSTORE][refund-convergence] callback 入口 projectId={}, refundCaseNo={}, actualAmount={}, traceId={}",
                expectedProjectId, refundCaseNo, actualRefundAmountCny, traceId);
        RefundScope scope = loadScope(refundCaseMapper.selectByRefundCaseNo(refundCaseNo));
        verifyProject(expectedProjectId, scope.order());
        InstoreChannelLifecycle.RefundSettlementOutcome outcome = lifecycle.applyBuyerRefundResult(
                refundCaseNo, InstoreRefundStatus.SUCCESS.name(), actualRefundAmountCny,
                cashierRefundReference, null, traceId);
        throwIfMismatch(outcome);
        dispatchNextClaimed(scope, outcome, traceId);
    }

    /** 采购项目侧查询（接口⑥）：先校验项目归属，再在本地非终态时按原 refundCaseNo 权威查 SaaS 收银台。 */
    public InstoreChannelLifecycle.RefundStatusView queryAndConvergeRefund(Long expectedProjectId,
                                                                           String channelCode,
                                                                           String channelRefundNo,
                                                                           String traceId) {
        log.info("[INSTORE][refund-convergence] query 入口 projectId={}, channelCode={}, channelRefundNo={}, traceId={}",
                expectedProjectId, channelCode, channelRefundNo, traceId);
        RefundScope scope = loadScope(refundCaseMapper.selectByChannelRefundNo(channelCode, channelRefundNo));
        verifyProject(expectedProjectId, scope.order());
        return queryIfNecessary(scope, traceId);
    }

    /** Timer 恢复入口：只接受数据库已固化的原 refundCaseNo，不接受临时路由字段。 */
    public InstoreChannelLifecycle.RefundStatusView queryAndConvergeRefundByRefundCaseNo(
            String refundCaseNo, String traceId) {
        log.info("[INSTORE][refund-convergence] timer query 入口 refundCaseNo={}, traceId={}",
                refundCaseNo, traceId);
        return queryIfNecessary(loadScope(refundCaseMapper.selectByRefundCaseNo(refundCaseNo)), traceId);
    }

    /**
     * 派发外呼（受理/取消抢占后与终态派发下一个共用）：使用订单固化的同一项目和收银台档案版本，
     * 按原 refundCaseNo 调 refund；外呼异常保留原号，交由回调或 Timer 权威查询收敛。
     */
    public void dispatchClaimedRefundCase(String refundCaseNo, String invocationId, String traceId) {
        RefundScope scope = loadScope(refundCaseMapper.selectByRefundCaseNo(refundCaseNo));
        InstoreRefundCaseDO refundCase = scope.refundCase();
        if (refundCase.getStatus() == null
                || refundCase.getStatus() != InstoreRefundStatus.BUYER_REFUNDING.getCode()) {
            log.info("[INSTORE][refund-convergence] Case 非退款中，跳过外呼 refundCaseNo={}, status={}",
                    refundCaseNo, refundCase.getStatus());
            return;
        }
        BuyerIntegrationRoute route = resolveRoute(scope.order());
        long startMs = System.currentTimeMillis();
        log.info("[INSTORE][cashier] refund 请求 projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantId={}, invocationId={}, refundCaseNo={}, outTradeNo={}, amount={}",
                route.projectId(), route.profileVersionId(), route.sourceSystem(),
                route.externalTenantId(), invocationId, refundCaseNo,
                scope.flow().getOutTradeNo(), refundCase.getChannelRefundAmount());
        BuyerCashierPort.RefundResult result;
        try {
            result = cashierPort.refund(new BuyerCashierPort.RefundCommand(route, invocationId,
                    refundCaseNo, scope.flow().getOutTradeNo(), refundCase.getChannelRefundAmount()));
            log.info("[INSTORE][cashier] refund 响应 projectId={}, refundCaseNo={}, status={}, actualAmount={}, "
                            + "cashierRefundReference={}, cost={}ms",
                    route.projectId(), refundCaseNo, result.status(), result.actualRefundAmountCny(),
                    result.cashierRefundReference(), System.currentTimeMillis() - startMs);
        } catch (RuntimeException ex) {
            log.warn("[INSTORE][cashier] refund 异常，保留原号待权威查询 refundCaseNo={}, invocationId={}, cost={}ms",
                    refundCaseNo, invocationId, System.currentTimeMillis() - startMs, ex);
            return;
        }
        handleRefundResponse(scope, mapRefundCallStatus(result.status()), result.actualRefundAmountCny(),
                result.cashierRefundReference(), result.errorCode(), traceId);
    }

    /** 收银台 refund 响应枚举到 Lifecycle 退款状态：SUCCEEDED→SUCCESS、REJECTED→FAILED，其余非终态保持。 */
    private static String mapRefundCallStatus(BuyerCashierPort.RefundStatus status) {
        return switch (status) {
            case SUCCEEDED -> InstoreRefundStatus.SUCCESS.name();
            case REJECTED -> InstoreRefundStatus.FAILED.name();
            case PROCESSING, UNKNOWN -> status.name();
        };
    }

    private InstoreChannelLifecycle.RefundStatusView queryIfNecessary(RefundScope scope, String traceId) {
        InstoreRefundCaseDO refundCase = scope.refundCase();
        if (isTerminal(refundCase.getStatus())) {
            log.info("[INSTORE][refund-convergence] 本地已终态，跳过外呼 refundCaseNo={}, status={}",
                    refundCase.getRefundCaseNo(), refundCase.getStatus());
            return lifecycle.queryRefundStatus(refundCase.getChannelCode(), refundCase.getChannelRefundNo());
        }
        BuyerIntegrationRoute route = resolveRoute(scope.order());
        long startMs = System.currentTimeMillis();
        log.info("[INSTORE][cashier] queryRefund 请求 projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantId={}, refundCaseNo={}",
                route.projectId(), route.profileVersionId(), route.sourceSystem(),
                route.externalTenantId(), refundCase.getRefundCaseNo());
        BuyerCashierPort.QueryRefundResult result = cashierPort.queryRefund(
                new BuyerCashierPort.QueryRefundCommand(route, refundCase.getRefundCaseNo()));
        log.info("[INSTORE][cashier] queryRefund 响应 projectId={}, refundCaseNo={}, status={}, actualAmount={}, "
                        + "cost={}ms",
                route.projectId(), refundCase.getRefundCaseNo(), result.status(),
                result.actualRefundAmountCny(), System.currentTimeMillis() - startMs);
        if (result.status() == BuyerCashierPort.RefundQueryStatus.SUCCEEDED) {
            handleRefundResponse(scope, InstoreRefundStatus.SUCCESS.name(), result.actualRefundAmountCny(),
                    result.cashierRefundReference(), null, traceId);
        } else if (result.status() == BuyerCashierPort.RefundQueryStatus.FAILED) {
            handleRefundResponse(scope, InstoreRefundStatus.FAILED.name(), null, result.cashierRefundReference(),
                    result.errorCode(), traceId);
        }
        return lifecycle.queryRefundStatus(refundCase.getChannelCode(), refundCase.getChannelRefundNo());
    }

    /** 回调/查询/派发共用的结果收敛：SUCCESS 与 FAILED 之外（处理中/未知）不推进本地状态。 */
    private void handleRefundResponse(RefundScope scope, String refundStatus, Long actualAmount,
                                      String cashierRefundReference, String errorCode, String traceId) {
        if (!InstoreRefundStatus.SUCCESS.name().equals(refundStatus)
                && !InstoreRefundStatus.FAILED.name().equals(refundStatus)) {
            return;
        }
        InstoreChannelLifecycle.RefundSettlementOutcome outcome = lifecycle.applyBuyerRefundResult(
                scope.refundCase().getRefundCaseNo(), refundStatus, actualAmount,
                cashierRefundReference, errorCode, traceId);
        throwIfMismatch(outcome);
        dispatchNextClaimed(scope, outcome, traceId);
    }

    private void dispatchNextClaimed(RefundScope scope,
                                     InstoreChannelLifecycle.RefundSettlementOutcome outcome,
                                     String traceId) {
        if (!outcome.nextClaimed()) {
            return;
        }
        log.info("[INSTORE][refund-convergence] 派发下一个排队 Case nextRefundCaseNo={}",
                outcome.nextClaimedRefundCaseNo());
        dispatchClaimedRefundCase(outcome.nextClaimedRefundCaseNo(),
                outcome.nextClaimedOperationNo(), traceId);
    }

    private void throwIfMismatch(InstoreChannelLifecycle.RefundSettlementOutcome outcome) {
        if (outcome.amountMismatch()) {
            throw exception(INSTORE_REFUND_AMOUNT_MISMATCH);
        }
    }

    private BuyerIntegrationRoute resolveRoute(InstoreChannelOrderDO order) {
        return routeResolver.resolveFrozen(order.getProjectId(), order.getCashierProfileVersionId(),
                BuyerIntegrationCapabilityCode.CASHIER);
    }

    private RefundScope loadScope(InstoreRefundCaseDO refundCase) {
        if (refundCase == null) {
            throw exception(INSTORE_REFUND_CASE_NOT_FOUND);
        }
        InstorePaymentFlowDO flow = paymentFlowMapper.selectById(refundCase.getPaymentFlowId());
        if (flow == null) {
            throw exception(INSTORE_REFUND_CASE_NOT_FOUND);
        }
        InstoreChannelOrderDO order = orderMapper.selectById(refundCase.getInstoreOrderId());
        if (order == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        return new RefundScope(refundCase, flow, order);
    }

    private static void verifyProject(Long expectedProjectId, InstoreChannelOrderDO order) {
        if (expectedProjectId == null || !expectedProjectId.equals(order.getProjectId())) {
            throw exception(INSTORE_TENANT_SCOPE_CONFLICT);
        }
    }

    /** 退款 Case 终态：成功/失败/已完成/人工处理；终态时跳过权威查询外呼。 */
    private static boolean isTerminal(Short status) {
        return status != null
                && (status == InstoreRefundStatus.SUCCESS.getCode()
                || status == InstoreRefundStatus.FAILED.getCode()
                || status == InstoreRefundStatus.COMPLETED.getCode()
                || status == InstoreRefundStatus.MANUAL.getCode());
    }

    private record RefundScope(InstoreRefundCaseDO refundCase, InstorePaymentFlowDO flow,
                               InstoreChannelOrderDO order) {
    }
}
