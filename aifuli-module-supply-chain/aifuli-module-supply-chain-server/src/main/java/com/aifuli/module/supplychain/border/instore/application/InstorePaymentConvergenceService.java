package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ORDER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_PAYMENT_FLOW_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT;

/**
 * 到店购支付统一收敛服务：SaaS 回调优先，本地非终态时按原 outTradeNo 权威查询兜底。
 *
 * <p>渠道查询与 PersistentTimer 必须复用本服务；查询成功和回调成功最终都调用
 * {@link InstoreChannelLifecycle#confirmBuyerPayment(String, long, String, String, String)}，
 * 由既有状态判断和 CAS 保障并发幂等。网络调用不在本地事务中执行。
 * 详见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案} 和
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstorePaymentConvergenceService {

    private final InstorePaymentFlowMapper paymentFlowMapper;
    private final InstoreChannelOrderMapper orderMapper;
    private final BuyerIntegrationRouteResolver routeResolver;
    private final BuyerCashierPort cashierPort;
    private final InstoreChannelLifecycle lifecycle;

    /** 采购项目侧查询：先校验订单项目归属，再在本地非终态时权威查 SaaS 收银台。 */
    public InstoreChannelLifecycle.PaymentStatusView queryAndConvergePayment(
            Long expectedProjectId, String channelCode, String tradeId, String traceId) {
        log.info("[INSTORE][payment-convergence] query 入口 projectId={}, channelCode={}, tradeId={}, traceId={}",
                expectedProjectId, channelCode, tradeId, traceId);
        InstorePaymentFlowDO flow = paymentFlowMapper.selectByChannelTrade(channelCode, tradeId);
        PaymentScope scope = loadScope(flow);
        verifyProject(expectedProjectId, scope.order());
        return queryIfNecessary(scope, traceId);
    }

    /** Timer 恢复入口：只接受数据库已固化的原 outTradeNo，不接受临时路由字段。 */
    public InstoreChannelLifecycle.PaymentStatusView queryAndConvergePaymentByOutTradeNo(
            String outTradeNo, String traceId) {
        log.info("[INSTORE][payment-convergence] timer query 入口 outTradeNo={}, traceId={}",
                outTradeNo, traceId);
        InstorePaymentFlowDO flow = paymentFlowMapper.selectByOutTradeNo(outTradeNo);
        return queryIfNecessary(loadScope(flow), traceId);
    }

    /**
     * 回调优先入口：校验已认证项目拥有该 outTradeNo 后直接进入 Lifecycle，不先调用查询接口。
     */
    public void confirmPayment(Long expectedProjectId, String outTradeNo, long actualAmountCny,
                               String cashierOrderNo, String cashierTransactionNo, String traceId) {
        PaymentScope scope = loadScope(paymentFlowMapper.selectByOutTradeNo(outTradeNo));
        verifyProject(expectedProjectId, scope.order());
        lifecycle.confirmBuyerPayment(outTradeNo, actualAmountCny,
                cashierOrderNo, cashierTransactionNo, traceId);
    }

    private InstoreChannelLifecycle.PaymentStatusView queryIfNecessary(PaymentScope scope, String traceId) {
        InstorePaymentFlowDO flow = scope.flow();
        if (isTerminal(flow.getPayStatus())) {
            log.info("[INSTORE][payment-convergence] 本地已终态，跳过外呼 outTradeNo={}, payStatus={}",
                    flow.getOutTradeNo(), flow.getPayStatus());
            return lifecycle.queryPaymentStatus(flow.getChannelCode(), flow.getTradeId());
        }

        InstoreChannelOrderDO order = scope.order();
        BuyerIntegrationRoute route = routeResolver.resolveFrozen(
                order.getProjectId(), order.getCashierProfileVersionId(),
                BuyerIntegrationCapabilityCode.CASHIER);
        long startMs = System.currentTimeMillis();
        log.info("[INSTORE][cashier] queryPayment 请求 projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantId={}, outTradeNo={}",
                route.projectId(), route.profileVersionId(), route.sourceSystem(),
                route.externalTenantId(), flow.getOutTradeNo());
        BuyerCashierPort.QueryPaymentResult result = cashierPort.queryPayment(
                new BuyerCashierPort.QueryPaymentCommand(route, flow.getOutTradeNo()));
        log.info("[INSTORE][cashier] queryPayment 响应 projectId={}, outTradeNo={}, status={}, "
                        + "confirmedAmount={}, cashierOrderNo={}, cost={}ms",
                route.projectId(), flow.getOutTradeNo(), result.status(), result.confirmedAmountCny(),
                result.cashierOrderNo(), System.currentTimeMillis() - startMs);
        if (result.status() == BuyerCashierPort.PaymentQueryStatus.SUCCEEDED) {
            lifecycle.confirmBuyerPayment(flow.getOutTradeNo(), result.confirmedAmountCny(),
                    result.cashierOrderNo(), result.cashierTransactionNo(), traceId);
        }
        return lifecycle.queryPaymentStatus(flow.getChannelCode(), flow.getTradeId());
    }

    private PaymentScope loadScope(InstorePaymentFlowDO flow) {
        if (flow == null) {
            throw exception(INSTORE_PAYMENT_FLOW_NOT_FOUND);
        }
        InstoreChannelOrderDO order = orderMapper.selectById(flow.getInstoreOrderId());
        if (order == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        return new PaymentScope(flow, order);
    }

    private static void verifyProject(Long expectedProjectId, InstoreChannelOrderDO order) {
        if (expectedProjectId == null || !expectedProjectId.equals(order.getProjectId())) {
            throw exception(INSTORE_TENANT_SCOPE_CONFLICT);
        }
    }

    private static boolean isTerminal(short status) {
        return status == InstorePaymentFlowStatus.PAID.getCode()
                || status == InstorePaymentFlowStatus.FAILED.getCode()
                || status == InstorePaymentFlowStatus.CANCELLED.getCode()
                || status == InstorePaymentFlowStatus.MANUAL.getCode();
    }

    private record PaymentScope(InstorePaymentFlowDO flow, InstoreChannelOrderDO order) {
    }
}
