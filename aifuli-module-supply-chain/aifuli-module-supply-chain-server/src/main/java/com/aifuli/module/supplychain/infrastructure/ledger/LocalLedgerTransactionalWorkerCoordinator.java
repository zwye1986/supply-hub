package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.configuration.BudgetContext;
import com.aifuli.module.supplychain.infrastructure.configuration.BudgetTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 纯本地完整业务事务编排与有界重试协调器。
 * 依据 V4A / R01 获审规范：
 * - 纯本地完整 worker 最多 2 次执行（首次 + 1 次重跑）；
 * - 仅本地账本 slot 锁阶段发生同一 SQLException 且 vendorCode=1213、SQLState=40001，
 *   并且 afterCompletion 为 ROLLED_BACK、连接及事务资源完全清理后方可重跑；
 * - 1205、1062、3819、其他错误、UNKNOWN 或提交不确定状态一律禁止重试，保留原异常 cause 向上抛出；
 * - 事务外 10-100ms 随机退避，单调 15s 命令截止时间，准入 R >= 3500ms，向下取整不足 1 秒拒绝；
 * - A 选品、定价 RPC、六旧 Handler 与引擎不接入重试，保持隔离。
 */
@Slf4j
@Service
public class LocalLedgerTransactionalWorkerCoordinator {

    private final PlatformTransactionManager transactionManager;

    public LocalLedgerTransactionalWorkerCoordinator(
            @Qualifier("supplyChainTransactionManager") PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public <T> T executeWorker(String commandId, LocalLedgerWorker<T> worker) {
        // 禁止活动外层事务（防逃逸与污染）
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("纯本地完整worker编排器禁止在既有活动事务中启动");
        }

        boolean createdContext = false;
        BudgetContext context = BudgetContext.current();
        if (context == null) {
            long now = System.nanoTime();
            context = BudgetContext.create(now, now + BudgetContext.TOTAL_COMMAND_TIMEOUT_NANOS);
            BudgetContext.set(context);
            createdContext = true;
        }

        try {
            // 准入检查
            context.checkAdmission();

            for (int attempt = 1; attempt <= 2; attempt++) {
                context.checkAdmission();
                byte[] attemptNonce = generateNonce();
                AtomicInteger completionState = new AtomicInteger(-1);

                TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
                txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

                long remainingNanos = context.remainingNanos() - BudgetContext.CLEANUP_RESERVE_NANOS;
                int remainingSec = (int) Math.max(1L, remainingNanos / 1_000_000_000L);
                txTemplate.setTimeout(Math.min(6, remainingSec));

                final int currentAttempt = attempt;
                final BudgetContext finalContext = context;

                try {
                    return txTemplate.execute(status -> {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                if (readOnly || status.isRollbackOnly() || finalContext.isCancelled()) {
                                    throw new BudgetTimeoutException("提交前守卫检测到只读、回滚或已取消");
                                }
                                if (System.nanoTime() >= finalContext.getCommandDeadline() - BudgetContext.CLEANUP_RESERVE_NANOS) {
                                    throw new BudgetTimeoutException("提交前守卫检测到已超过预算执行期限");
                                }
                            }

                            @Override
                            public void afterCompletion(int state) {
                                completionState.set(state);
                            }
                        });

                        return worker.execute(attemptNonce, currentAttempt);
                    });
                } catch (RuntimeException e) {
                    boolean allowRetry = (currentAttempt == 1)
                            && (e instanceof LocalLedgerDeadlockException)
                            && LocalLedgerDeadlockDetector.isExactDeadlock(e)
                            && (completionState.get() == TransactionSynchronization.STATUS_ROLLED_BACK)
                            && (!TransactionSynchronizationManager.isActualTransactionActive())
                            && (!TransactionSynchronizationManager.isSynchronizationActive())
                            && (!context.isCleanupFailed())
                            && (!context.isCancelled());

                    if (!allowRetry) {
                        throw e;
                    }

                    // 事务外有限退避 10-100ms
                    long backoffMs = ThreadLocalRandom.current().nextLong(10, 101);
                    long backoffNanos = TimeUnit.MILLISECONDS.toNanos(backoffMs);
                    long remainingAfterBackoff = context.remainingNanos() - backoffNanos;
                    if (remainingAfterBackoff < BudgetContext.ADMISSION_THRESHOLD_NANOS) {
                        throw new BudgetTimeoutException("事务外退避后剩余预算不足准入门槛 3500ms", e);
                    }

                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BudgetTimeoutException("事务外退避被中断", ie);
                    }
                    context.checkAdmission();
                }
            }

            throw new BudgetTimeoutException("工作者重试次数耗尽（最多2次尝试）");
        } finally {
            if (createdContext) {
                BudgetContext.clear();
            }
        }
    }

    private byte[] generateNonce() {
        byte[] nonce = new byte[16];
        ThreadLocalRandom.current().nextBytes(nonce);
        return nonce;
    }
}
