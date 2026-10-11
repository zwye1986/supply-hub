package com.aifuli.module.supplychain.tradecore.supply.application;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 生命周期写入口在第一条业务读写前核验实际隔离上下文，阻止默认RR外层事务污染同步定价重算视图。
 * 自调用无法切换隔离级别，因此本守卫与入口局部READ_COMMITTED同时使用；无事务直调仅用于纯单测。
 * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范。
 */
public final class SupplierLifecycleTransactions {
    private SupplierLifecycleTransactions() { }
    public static void requireReadCommitted() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("Supplier lifecycle writes require an actual READ_COMMITTED transaction");
        }
    }
}
