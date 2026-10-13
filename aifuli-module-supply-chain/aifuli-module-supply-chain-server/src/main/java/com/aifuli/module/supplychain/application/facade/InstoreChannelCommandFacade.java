package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.border.instore.application.InstoreOrderCreationCoordinator;
import com.aifuli.module.supplychain.border.instore.application.InstoreChannelLifecycle;
import com.aifuli.module.supplychain.border.instore.application.InstoreChannelUserBindingResolver;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.border.instore.application.InstorePaymentConvergenceService;
import com.aifuli.module.supplychain.border.instore.application.InstoreRefundConvergenceService;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 到店购建单应用编排：冻结可信项目路由、本地建单、CAS 抢占并在事务外调用 SaaS 收银台。
 *
 * <p>本类自身禁止开启数据库事务；每个 {@link InstoreChannelLifecycle} 方法都是独立本地事务，
 * 收银台网络调用位于两段事务之间。{@code projectId} 决定业务归属，
 * {@code externalTenantId} 只能由冻结的 CASHIER 档案版本解析。
 * 详见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案} 和
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstoreChannelCommandFacade {

    private static final long CASHIER_EXPIRES_MINUTES = 30L;

    private final InstoreChannelLifecycle lifecycle;
    private final InstoreOrderCreationCoordinator creationCoordinator;
    private final BuyerIntegrationRouteResolver routeResolver;
    private final BuyerCashierPort cashierPort;
    private final InstorePaymentConvergenceService convergenceService;
    private final InstoreRefundConvergenceService refundConvergenceService;
    private final InstoreChannelUserBindingResolver bindingResolver;

    /**
     * Wire 层建单编排：先解析稳定绑定获得采购商归属，再按项目解析当前 CASHIER 档案版本，
     * 组装既有 {@link PreparePaymentCommand} 委托 {@link #createPayment}。
     *
     * <p>projectId/channelCode 来自可信渠道上下文；绑定缺失或停用抛绑定缺失，
     * 当前档案版本未发布抛 IllegalStateException（内部配置错误，非渠道错误码）。详见
     * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#5-facade-wire-编排方案-a}。</p>
     */
    public PaymentTicket createPaymentOrder(WireCreatePaymentCommand command) {
        Objects.requireNonNull(command, "command");
        log.info("[INSTORE][facade] createPaymentOrder 入口 projectId={}, channelCode={}, channelOrderId={}, tradeId={}, payAmount={}, traceId={}",
                command.projectId(), command.channelCode(), command.channelOrderId(),
                command.tradeId(), command.payAmount(), command.traceId());
        InstoreChannelUserBindingResolver.BindingSnapshot binding = bindingResolver.requireActiveBinding(
                command.projectId(), command.userCode(), command.channelCode());
        BuyerIntegrationRoute cashierRoute = routeResolver.resolveCurrent(
                command.projectId(), BuyerIntegrationCapabilityCode.CASHIER);
        return createPayment(new PreparePaymentCommand(
                command.channelCode(), command.channelOrderId(), command.tradeId(),
                binding.buyerId(), command.projectId(), cashierRoute.profileVersionId(),
                command.userCode(), command.userCode(), command.payAmount(), command.orderType(),
                command.totalAmount(), command.companyCode(), command.externalMerchantId(),
                command.shopCode(), command.shopName(), command.subject(), command.traceId()));
    }

    /**
     * 创建支付单并尝试拉起收银台。
     *
     * <p>幂等回放和抢占失败只返回原 {@code outTradeNo}；外呼超时同样保留原号，
     * 交由支付回调或权威查询收敛，禁止自动重创收银台订单。</p>
     */
    public PaymentTicket createPayment(PreparePaymentCommand command) {
        Objects.requireNonNull(command, "command");
        log.info("[INSTORE][facade] createPayment 入口 projectId={}, profileVersionId={}, "
                        + "channelCode={}, channelOrderId={}, tradeId={}, amount={}, traceId={}",
                command.projectId(), command.cashierProfileVersionId(), command.channelCode(),
                command.channelOrderId(), command.tradeId(), command.payAmount(), command.traceId());

        BuyerIntegrationRoute route = routeResolver.resolveFrozen(
                command.projectId(), command.cashierProfileVersionId(), BuyerIntegrationCapabilityCode.CASHIER);
        InstoreChannelLifecycle.PreparedPayment prepared = creationCoordinator.create(
                toLifecycleCommand(command, route));
        if (prepared.idempotentReplay()) {
            log.info("[INSTORE][facade] createPayment 幂等回放 projectId={}, outTradeNo={}, hasCashierUrl={}",
                    prepared.projectId(), prepared.outTradeNo(), prepared.cashierUrl() != null);
            return toTicket(prepared, prepared.cashierUrl(), prepared.payStatus());
        }

        InstoreChannelLifecycle.ClaimResult claim = lifecycle.claimPaymentPreparation(
                prepared.invocationId(), prepared.operationVersion());
        if (!claim.claimed()) {
            log.info("[INSTORE][facade] createPayment 抢占失败 projectId={}, outTradeNo={}, invocationId={}",
                    prepared.projectId(), prepared.outTradeNo(), prepared.invocationId());
            return toTicket(prepared, prepared.cashierUrl(), prepared.payStatus());
        }

        long startMs = System.currentTimeMillis();
        BuyerCashierPort.CreateCashierOrderResult cashierResult;
        try {
            log.info("[INSTORE][cashier] create 请求 projectId={}, profileVersionId={}, sourceSystem={}, "
                            + "externalTenantId={}, invocationId={}, outTradeNo={}, amount={}",
                    route.projectId(), route.profileVersionId(), route.sourceSystem(), route.externalTenantId(),
                    prepared.invocationId(), prepared.outTradeNo(), prepared.payAmount());
            cashierResult = cashierPort.createCashierOrder(new BuyerCashierPort.CreateCashierOrderCommand(
                    route, prepared.invocationId(), prepared.outTradeNo(), prepared.buyerUserId(),
                    prepared.payAmount(), prepared.subject(),
                    OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(CASHIER_EXPIRES_MINUTES)));
            log.info("[INSTORE][cashier] create 响应 projectId={}, outTradeNo={}, status={}, cashierOrderNo={}, "
                            + "hasCashierUrl={}, cost={}ms",
                    route.projectId(), prepared.outTradeNo(), cashierResult.status(),
                    cashierResult.cashierOrderNo(), cashierResult.launchDescriptor() != null,
                    System.currentTimeMillis() - startMs);
        } catch (RuntimeException ex) {
            log.warn("[INSTORE][cashier] create 异常，保留原号待权威查询 projectId={}, outTradeNo={}, "
                            + "invocationId={}, cost={}ms",
                    route.projectId(), prepared.outTradeNo(), prepared.invocationId(),
                    System.currentTimeMillis() - startMs, ex);
            return toTicket(prepared, null, InstorePaymentFlowStatus.PROCESSING.getCode());
        }

        lifecycle.recordCashierOrder(prepared.invocationId(), claim.operationVersion(), cashierResult);
        short payStatus = cashierResult.status() == BuyerCashierPort.CashierOrderStatus.REJECTED
                ? InstorePaymentFlowStatus.FAILED.getCode()
                : InstorePaymentFlowStatus.PROCESSING.getCode();
        return toTicket(prepared, cashierResult.launchDescriptor(), payStatus);
    }

    /**
     * 渠道支付查询：本地非终态时使用订单冻结的 CASHIER 档案和原 outTradeNo 权威查询。
     *
     * <p>返回值包装为 Facade 自有视图，RPC 实现层不得依赖 border 包的 Lifecycle 视图。详见
     * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#3-interface-adapter-层}。</p>
     */
    public PaymentStatusView queryAndConvergePayment(QueryPaymentCommand command) {
        Objects.requireNonNull(command, "command");
        InstoreChannelLifecycle.PaymentStatusView view = convergenceService.queryAndConvergePayment(
                command.projectId(), command.channelCode(), command.tradeId(), command.traceId());
        return new PaymentStatusView(view.paymentFlowNo(), view.outTradeNo(), view.tradeId(),
                view.payStatus(), view.payAmount(), view.buyerPaidAmount(),
                view.cashierReference(), view.externalTransactionId(), view.payTime());
    }

    /**
     * 退款受理（接口⑤联调切片）：受理事务提交后，抢占成功的 Case 按原 refundCaseNo 外呼收银台；
     * 排队中的 Case 不外呼，等待当前活动退款终态后由收敛服务派发。
     */
    public RefundAcceptance acceptRefund(AcceptRefundCommand command) {
        Objects.requireNonNull(command, "command");
        log.info("[INSTORE][facade] acceptRefund 入口 projectId={}, channelCode={}, channelRefundNo={}, tradeId={}, "
                        + "refundType={}, refundAmount={}, traceId={}",
                command.projectId(), command.channelCode(), command.channelRefundNo(), command.tradeId(),
                command.refundType(), command.channelRefundAmount(), command.traceId());
        InstoreChannelLifecycle.AcceptRefundOutcome outcome = lifecycle.acceptRefund(
                new InstoreChannelLifecycle.AcceptRefundCommand(command.projectId(),
                        command.channelCode(), command.channelRefundNo(), command.tradeId(),
                        command.refundType(), command.channelRefundAmount(), command.traceId()));
        if (outcome.claimedForDispatch()) {
            refundConvergenceService.dispatchClaimedRefundCase(outcome.refundCaseNo(),
                    outcome.operationNo(), command.traceId());
        }
        return new RefundAcceptance(outcome.refundCaseNo(), outcome.outTradeNo(), outcome.refundStatus(),
                outcome.claimedForDispatch());
    }

    /**
     * 取消支付流水（接口④）：未支付走取消释放；已支付转全额 CANCEL_REFUND 并共用串行派发闭环。
     */
    public CancelPaymentTicket cancelPaymentFlow(CancelPaymentCommand command) {
        Objects.requireNonNull(command, "command");
        log.info("[INSTORE][facade] cancelPaymentFlow 入口 projectId={}, channelCode={}, tradeId={}, traceId={}",
                command.projectId(), command.channelCode(), command.tradeId(), command.traceId());
        InstoreChannelLifecycle.CancelPaymentFlowResult result = lifecycle.cancelPaymentFlow(
                new InstoreChannelLifecycle.CancelPaymentFlowCommand(
                        command.projectId(), command.channelCode(), command.tradeId(), command.traceId()));
        if (result.claimedForDispatch()) {
            refundConvergenceService.dispatchClaimedRefundCase(result.refundCaseNo(),
                    result.operationNo(), command.traceId());
        }
        return new CancelPaymentTicket(result.outTradeNo(), result.refundCaseNo(),
                result.convertedToRefund(), result.claimedForDispatch());
    }

    /**
     * 渠道退款查询（接口⑥）：委托统一收敛服务，本地非终态时按原 refundCaseNo 权威查 SaaS 收银台。
     *
     * <p>返回值包装为 Facade 自有视图，RPC 实现层不得依赖 border 包的 Lifecycle 视图。详见
     * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#3-interface-adapter-层}。</p>
     */
    public RefundStatusView queryAndConvergeRefund(QueryRefundCommand command) {
        Objects.requireNonNull(command, "command");
        InstoreChannelLifecycle.RefundStatusView view = refundConvergenceService.queryAndConvergeRefund(
                command.projectId(), command.channelCode(), command.channelRefundNo(), command.traceId());
        return new RefundStatusView(view.refundCaseNo(), view.channelRefundNo(), view.refundType(),
                view.status(), view.channelRefundAmount(), view.buyerActualRefundAmount(),
                view.outTradeNo(), view.refundTime(), view.refuseReason());
    }

    private static InstoreChannelLifecycle.CreatePaymentOrderCommand toLifecycleCommand(
            PreparePaymentCommand command, BuyerIntegrationRoute route) {
        return new InstoreChannelLifecycle.CreatePaymentOrderCommand(
                command.channelCode(), command.channelOrderId(), command.tradeId(), command.buyerId(),
                command.projectId(), command.cashierProfileVersionId(), route.sourceSystem(),
                route.externalTenantId(), command.buyerUserId(), command.userCode(), command.payAmount(),
                command.orderType(), command.totalAmount(), command.companyCode(), command.externalMerchantId(),
                command.shopCode(), command.shopName(), command.subject(), command.traceId());
    }

    private static PaymentTicket toTicket(InstoreChannelLifecycle.PreparedPayment prepared,
                                          String cashierUrl, short payStatus) {
        return new PaymentTicket(prepared.orderNo(), prepared.outTradeNo(), cashierUrl, payStatus);
    }

    /** Wire 层建单命令：projectId/channelCode 来自可信渠道上下文，其余字段来自渠道 DTO。 */
    public record WireCreatePaymentCommand(Long projectId, String channelCode, String channelOrderId,
                                           String tradeId, String userCode, long payAmount, int orderType,
                                           long totalAmount, String companyCode, String externalMerchantId,
                                           String shopCode, String shopName, String subject, String traceId) {
    }

    /**
     * Facade 内部命令。buyerId/projectId 来自认证上下文，业务 Wire DTO 禁止声明这些字段。
     */
    public record PreparePaymentCommand(String channelCode, String channelOrderId, String tradeId,
                                        Long buyerId, Long projectId, Long cashierProfileVersionId,
                                        String buyerUserId, String userCode, long payAmount, int orderType,
                                        long totalAmount, String companyCode, String externalMerchantId,
                                        String shopCode, String shopName, String subject, String traceId) {
    }

    /** 项目侧支付查询内部命令；projectId 来自认证上下文，不属于渠道 Wire DTO。 */
    public record QueryPaymentCommand(Long projectId, String channelCode, String tradeId, String traceId) {
    }

    /** 退款受理内部命令。 */
    public record AcceptRefundCommand(Long projectId, String channelCode, String channelRefundNo, String tradeId,
                                      String refundType, long channelRefundAmount, String traceId) {
    }

    /** 退款受理封闭结果：refundStatus 为 10 排队中 / 20 退款中。 */
    public record RefundAcceptance(String refundCaseNo, String outTradeNo, short refundStatus, boolean claimedForDispatch) {
    }

    /** 取消支付流水内部命令。 */
    public record CancelPaymentCommand(Long projectId, String channelCode, String tradeId, String traceId) {
    }

    /** 取消封闭结果：未支付仅 outTradeNo；已支付转退款时携带 refundCaseNo。 */
    public record CancelPaymentTicket(String outTradeNo, String refundCaseNo,
                                      boolean convertedToRefund, boolean claimedForDispatch) {
    }

    /** 项目侧退款查询内部命令。 */
    public record QueryRefundCommand(Long projectId, String channelCode, String channelRefundNo,
                                     String traceId) {
    }

    /** 建单封闭结果；cashierUrl 可能在处理中暂缺，调用方必须用原 outTradeNo 查询。 */
    public record PaymentTicket(String orderNo, String outTradeNo, String cashierUrl, short payStatus) {
    }

    /** 支付状态只读投影，字段语义与 sc_instore_payment_flow 列一致；供 RPC 实现层映射 Wire 响应。 */
    public record PaymentStatusView(String paymentFlowNo, String outTradeNo, String tradeId,
                                    short payStatus, long payAmount, Long buyerPaidAmount,
                                    String cashierReference, String externalTransactionId,
                                    Long payTime) {
    }

    /** 退款状态只读投影，字段语义与 sc_instore_refund_case 列一致；供 RPC 实现层映射 Wire 响应。 */
    public record RefundStatusView(String refundCaseNo, String channelRefundNo, String refundType,
                                   short status, long channelRefundAmount, Long buyerActualRefundAmount,
                                   String outTradeNo, Long refundTime, String refuseReason) {
    }
}
