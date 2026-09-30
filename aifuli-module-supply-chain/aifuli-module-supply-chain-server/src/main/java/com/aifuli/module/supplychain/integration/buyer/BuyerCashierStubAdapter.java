package com.aifuli.module.supplychain.integration.buyer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 采购商收银台 stub 适配器：本期 SaaS 商城收银台未就绪，提供配置化/写死实现供联调。
 *
 * <p>收银台就绪后替换为 Feign 实现（{@code BuyerCashierRpcAdapter}），Lifecycle/Facade 无需改动。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.3-本期范围裁剪决策}。</p>
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "buyerCashierRpcAdapter")
public class BuyerCashierStubAdapter implements BuyerCashierPort {

    /**
     * stub 收银台跳转描述前缀，可通过配置覆盖以联调不同收银台地址。
     * 对标原淘宝闪购 {@code mockElemeH5BaseUrl} 的配置化思路。
     */
    @Value("${aifuli.supply-chain.o2o.cashier.stub-launch-descriptor:stub://cashier/launch}")
    private String stubLaunchDescriptor;

    /**
     * stub 建单：固定返回 CREATED，cashierOrderNo 由 outTradeNo 派生。
     */
    @Override
    public CreateCashierOrderResult createCashierOrder(CreateCashierOrderCommand command) {
        log.info("[INSTORE][cashier-stub] create 请求 projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantId={}, invocationId={}, outTradeNo={}, amount={}, subject={}",
                command.route().projectId(), command.route().profileVersionId(), command.route().sourceSystem(),
                command.route().externalTenantId(), command.invocationId(), command.outTradeNo(),
                command.amountCny(), command.subject());
        return createStubOrder(command.outTradeNo(), command.amountCny());
    }

    /**
     * stub 查单：默认 PENDING，联调时由测试替身覆盖终态。
     */
    @Override
    public QueryPaymentResult queryPayment(QueryPaymentCommand command) {
        log.info("[INSTORE][cashier-stub] queryPayment 请求 projectId={}, profileVersionId={}, outTradeNo={}",
                command.route().projectId(), command.route().profileVersionId(), command.outTradeNo());
        QueryPaymentResult result = pendingPaymentResult();
        log.info("[INSTORE][cashier-stub] queryPayment 响应 outTradeNo={}, status={}",
                command.outTradeNo(), result.status());
        return result;
    }

    /**
     * stub 退款：默认 PROCESSING。
     */
    @Override
    public RefundResult refund(RefundCommand command) {
        log.info("[INSTORE][cashier-stub] refund 请求 projectId={}, profileVersionId={}, refundCaseNo={}, amount={}",
                command.route().projectId(), command.route().profileVersionId(),
                command.refundCaseNo(), command.amountCny());
        RefundResult result = new RefundResult(RefundStatus.PROCESSING, null, null, null,
                OffsetDateTime.now(ZoneOffset.UTC));
        log.info("[INSTORE][cashier-stub] refund 响应 refundCaseNo={}, status={}",
                command.refundCaseNo(), result.status());
        return result;
    }

    /**
     * stub 退款查单：默认 PROCESSING。
     */
    @Override
    public QueryRefundResult queryRefund(QueryRefundCommand command) {
        log.info("[INSTORE][cashier-stub] queryRefund 请求 projectId={}, profileVersionId={}, refundCaseNo={}",
                command.route().projectId(), command.route().profileVersionId(), command.refundCaseNo());
        QueryRefundResult result = new QueryRefundResult(RefundQueryStatus.PROCESSING,
                null, null, null, OffsetDateTime.now(ZoneOffset.UTC));
        log.info("[INSTORE][cashier-stub] queryRefund 响应 refundCaseNo={}, status={}",
                command.refundCaseNo(), result.status());
        return result;
    }

    @Deprecated
    @Override
    public CreateCashierOrderResult createLegacyCashierOrder(LegacyCreateCashierOrderCommand command) {
        log.info("[O2O][cashier-stub] create 请求 invocationId={}, outTradeNo={}, amount={}, subject={}",
                command.invocationId(), command.outTradeNo(), command.amountCny(), command.subject());
        return createStubOrder(command.outTradeNo(), command.amountCny());
    }

    @Deprecated
    @Override
    public QueryPaymentResult queryLegacyPayment(String outTradeNo) {
        log.info("[O2O][cashier-stub] queryPayment 请求 outTradeNo={}", outTradeNo);
        QueryPaymentResult result = pendingPaymentResult();
        log.info("[O2O][cashier-stub] queryPayment 响应 outTradeNo={}, status={}", outTradeNo, result.status());
        return result;
    }

    private CreateCashierOrderResult createStubOrder(String outTradeNo, long amountCny) {
        CreateCashierOrderResult result = new CreateCashierOrderResult(CashierOrderStatus.CREATED,
                "STUB-" + outTradeNo, stubLaunchDescriptor + "?outTradeNo=" + outTradeNo,
                amountCny, null, OffsetDateTime.now(ZoneOffset.UTC));
        log.info("[cashier-stub] create 响应 status={}, cashierOrderNo={}, launchDescriptor={}",
                result.status(), result.cashierOrderNo(), result.launchDescriptor());
        return result;
    }

    private QueryPaymentResult pendingPaymentResult() {
        return new QueryPaymentResult(PaymentQueryStatus.PENDING, null,
                null, null, null, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
