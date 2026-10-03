package com.aifuli.module.supplychain.border.fulfillment.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务外协调首次队列身份竞争；每次经真实Spring事务代理重新受理并读取已提交控制行。
 * 只重试专用身份唯一键竞争，其他异常原样传播。设计见
 * {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#41-入队}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeFrequencyAcceptanceCoordinator {
    private static final int MAX_ATTEMPTS = 3;
    private final FulfillmentAcceptanceLifecycle lifecycle;

    public FulfillmentAcceptanceLifecycle.AcceptanceSnapshot accept(long receiptId, FulfillmentAcceptanceLifecycle.PreparedAcceptance command) {
        return acceptPrepared(receiptId, command, false);
    }

    /** 直接下单仍在事务外重试队列身份竞争，每次进入Lifecycle真实代理的HTTP专用受理事务。 */
    public FulfillmentAcceptanceLifecycle.AcceptanceSnapshot acceptDirectProjectOrder(long receiptId, FulfillmentAcceptanceLifecycle.PreparedAcceptance command) {
        return acceptPrepared(receiptId, command, true);
    }

    private FulfillmentAcceptanceLifecycle.AcceptanceSnapshot acceptPrepared(long receiptId,
            FulfillmentAcceptanceLifecycle.PreparedAcceptance command, boolean directProjectOrder) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("直充限频受理协调入口必须在事务外调用");
        }
        log.debug("[直充限频] 受理协调入口 receiptId={}, buyerOrderNo={}", receiptId, command.buyerOrderNo());
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try { return directProjectOrder ? lifecycle.acceptDirectProjectOrder(receiptId, command) : lifecycle.accept(receiptId, command); }
            catch (RechargeFrequencyLifecycle.QueueIdentityCreationConflictException conflict) {
                if (attempt == MAX_ATTEMPTS) { throw conflict; }
                log.debug("[直充限频] 队列身份竞争回滚后重试 receiptId={}, attempt={}", receiptId, attempt);
            }
        }
        throw new IllegalStateException("直充限频受理重试不可达");
    }
}
