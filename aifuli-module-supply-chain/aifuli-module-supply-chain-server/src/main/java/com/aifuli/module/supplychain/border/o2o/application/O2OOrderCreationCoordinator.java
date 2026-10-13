package com.aifuli.module.supplychain.border.o2o.application;

import com.aifuli.module.supplychain.infrastructure.identity.*;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 回放探测、数字取号、建单事务按顺序分别执行；未知提交与编号冲突不自动重建。 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#53-o2o-与-instore}。
 */
@Service
@RequiredArgsConstructor
public class O2OOrderCreationCoordinator {
    private static final String TRANSACTION_KEY = "uk_sc_o2o_detail_transaction";
    private final O2OChannelLifecycle lifecycle;
    private final OrderNoAllocationService allocator;

    public O2OChannelLifecycle.PreparedPayment create(
            O2OChannelLifecycle.PreparePaymentCommand command) {
        OrderCreationSupport.requireOutsideTransaction();
        var replay = lifecycle.tryReplayPayment(command);
        if (replay != null) return replay;
        PreparedOrderNumbers numbers;
        try {
            numbers = allocator.prepare(OrderNoPurpose.ORDER_ONLY);
        } catch (RuntimeException unavailable) {
            var winner =
                    OrderCreationSupport.replayAfterFailure(
                            () -> lifecycle.tryReplayPayment(command), unavailable);
            if (winner != null) return winner;
            throw unavailable;
        }
        try {
            return lifecycle.createPaymentPreparation(command, numbers);
        } catch (DuplicateKeyException conflict) {
            if (!OrderCreationSupport.isKnownBusinessKey(conflict, TRANSACTION_KEY)) throw conflict;
            OrderCreationSupport.requireOutsideTransaction();
            var winner =
                    OrderCreationSupport.replayAfterFailure(
                            () -> lifecycle.tryReplayPayment(command), conflict);
            if (winner != null) return winner;
            throw conflict;
        }
    }
}
