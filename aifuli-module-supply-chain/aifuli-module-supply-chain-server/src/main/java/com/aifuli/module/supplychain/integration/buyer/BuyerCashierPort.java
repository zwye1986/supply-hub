package com.aifuli.module.supplychain.integration.buyer;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 采购商收银台出站端口：中台通过该 Port 拉起收银台并权威查支付/退款结果。
 *
 * <p>所有生产命令必须携带由采购项目已发布档案解析得到的 {@link BuyerIntegrationRoute}；
 * {@code externalTenantId} 只是路由坐标，不能代替 {@code projectId}。稳定键与重试规则见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案} 和
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#11.5-BuyerCashierPort-契约}。
 * INSTORE 建单由 SaaS 商城适配器签发会员 SSO 并生成真实 H5 地址；支付查询与退款能力仍保持
 * 裁剪态，O2O legacy 入口继续由兼容 stub 支撑。详见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md}。</p>
 */
public interface BuyerCashierPort {

    /** 创建收银台订单；稳定键 invocationId + outTradeNo，禁止自动重创，UNKNOWN 查原 outTradeNo。 */
    CreateCashierOrderResult createCashierOrder(CreateCashierOrderCommand command);

    /** 按 outTradeNo 权威查支付结果；可退避重试。 */
    QueryPaymentResult queryPayment(QueryPaymentCommand command);

    /** 发起退款；稳定键 invocationId + refundCaseNo，禁止自动重退，UNKNOWN 查原 refundCaseNo。 */
    RefundResult refund(RefundCommand command);

    /** 按原 refundCaseNo 权威查退款结果；可退避重试。 */
    QueryRefundResult queryRefund(QueryRefundCommand command);

    /**
     * O2O 旧链路尚未固化采购项目，仅允许其通过显式 legacy 入口继续受控联调。
     * INSTORE 禁止调用该方法。
     */
    @Deprecated
    CreateCashierOrderResult createLegacyCashierOrder(LegacyCreateCashierOrderCommand command);

    /** O2O 旧链路支付查询兼容入口；INSTORE 禁止调用。 */
    @Deprecated
    QueryPaymentResult queryLegacyPayment(String outTradeNo);

    /** 创建收银台订单命令。 */
    record CreateCashierOrderCommand(BuyerIntegrationRoute route, String invocationId,
                                     String outTradeNo, String buyerUserId,
                                     long amountCny, String subject, OffsetDateTime expiresAt) {
        public CreateCashierOrderCommand {
            requireCashierRoute(route);
            requireText(invocationId, "invocationId");
            requireText(outTradeNo, "outTradeNo");
            requireText(buyerUserId, "buyerUserId");
            requirePositive(amountCny, "amountCny");
            requireText(subject, "subject");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

    }

    /** 按原支付业务号进行权威查询，不得在查询时生成新号。 */
    record QueryPaymentCommand(BuyerIntegrationRoute route, String outTradeNo) {
        public QueryPaymentCommand {
            requireCashierRoute(route);
            requireText(outTradeNo, "outTradeNo");
        }
    }

    /** 创建收银台订单结果。 */
    record CreateCashierOrderResult(CashierOrderStatus status, String cashierOrderNo,
                                    String launchDescriptor, Long confirmedAmountCny,
                                    String errorCode, OffsetDateTime observedAt) {
    }

    /** 退款命令。 */
    record RefundCommand(BuyerIntegrationRoute route, String invocationId,
                         String refundCaseNo, String outTradeNo, long amountCny) {
        public RefundCommand {
            requireCashierRoute(route);
            requireText(invocationId, "invocationId");
            requireText(refundCaseNo, "refundCaseNo");
            requireText(outTradeNo, "outTradeNo");
            requirePositive(amountCny, "amountCny");
        }
    }

    /** 按原退款 Case 号进行权威查询，不得在查询时生成新号。 */
    record QueryRefundCommand(BuyerIntegrationRoute route, String refundCaseNo) {
        public QueryRefundCommand {
            requireCashierRoute(route);
            requireText(refundCaseNo, "refundCaseNo");
        }
    }

    /** 退款结果。 */
    record RefundResult(RefundStatus status, Long actualRefundAmountCny,
                        String cashierRefundReference, String errorCode,
                        OffsetDateTime observedAt) {
        public RefundResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(observedAt, "observedAt");
            requireSuccessfulRefundAmount(status == RefundStatus.SUCCEEDED, actualRefundAmountCny);
        }
    }

    /** 支付查询结果。 */
    record QueryPaymentResult(PaymentQueryStatus status, Long confirmedAmountCny,
                              String cashierOrderNo, String cashierTransactionNo,
                              String errorCode, OffsetDateTime observedAt) {
        public QueryPaymentResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(observedAt, "observedAt");
            if (status == PaymentQueryStatus.SUCCEEDED) {
                if (confirmedAmountCny == null || confirmedAmountCny <= 0) {
                    throw new IllegalArgumentException("confirmedAmountCny must be positive when payment succeeded");
                }
                requireText(cashierOrderNo, "cashierOrderNo");
                requireText(cashierTransactionNo, "cashierTransactionNo");
            }
        }
    }

    /** 退款查询结果。 */
    record QueryRefundResult(RefundQueryStatus status, Long actualRefundAmountCny,
                             String cashierRefundReference, String errorCode,
                             OffsetDateTime observedAt) {
        public QueryRefundResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(observedAt, "observedAt");
            requireSuccessfulRefundAmount(status == RefundQueryStatus.SUCCEEDED, actualRefundAmountCny);
        }
    }

    /** O2O 尚未迁移采购项目档案前的最小建单命令，不暴露伪造的路由字段。 */
    @Deprecated
    record LegacyCreateCashierOrderCommand(String invocationId, String outTradeNo,
                                           long amountCny, String subject,
                                           OffsetDateTime expiresAt) {
        public LegacyCreateCashierOrderCommand {
            requireText(invocationId, "invocationId");
            requireText(outTradeNo, "outTradeNo");
            requirePositive(amountCny, "amountCny");
            requireText(subject, "subject");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    private static void requireCashierRoute(BuyerIntegrationRoute route) {
        if (route == null || route.capability() != BuyerIntegrationCapabilityCode.CASHIER) {
            throw new IllegalArgumentException("route capability must be CASHIER");
        }
    }

    private static void requireSuccessfulRefundAmount(boolean succeeded, Long actualRefundAmountCny) {
        if (succeeded && (actualRefundAmountCny == null || actualRefundAmountCny <= 0)) {
            throw new IllegalArgumentException("actualRefundAmountCny must be positive when refund succeeded");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    enum CashierOrderStatus { CREATED, REJECTED, UNKNOWN }

    enum PaymentQueryStatus { NOT_FOUND, CREATING, PENDING, SUCCEEDED, FAILED, CLOSED, UNKNOWN }

    enum RefundStatus { PROCESSING, SUCCEEDED, REJECTED, UNKNOWN }

    enum RefundQueryStatus { NOT_FOUND, PROCESSING, SUCCEEDED, FAILED, UNKNOWN }
}
