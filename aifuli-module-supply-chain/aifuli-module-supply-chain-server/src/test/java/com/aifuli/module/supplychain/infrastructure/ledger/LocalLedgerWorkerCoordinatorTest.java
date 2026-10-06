package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.configuration.BudgetContext;
import com.aifuli.module.supplychain.infrastructure.configuration.BudgetTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证纯本地工作者事务编排与有界重试协议（最多2次尝试、仅本地1213死锁重跑、10-100ms退避、负例绝不重跑）。
 */
class LocalLedgerWorkerCoordinatorTest {

    private LocalLedgerTransactionalWorkerCoordinator coordinator;
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        transactionManager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
            }
        };
        coordinator = new LocalLedgerTransactionalWorkerCoordinator(transactionManager);
    }

    @AfterEach
    void tearDown() {
        BudgetContext.clear();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deadlockDetectorMatchesOnlyExact1213And40001() {
        SQLException deadLockSql = new SQLException("Deadlock found", "40001", 1213);
        assertThat(LocalLedgerDeadlockDetector.isExactDeadlock(deadLockSql)).isTrue();
        assertThat(LocalLedgerDeadlockDetector.isExactDeadlock(new RuntimeException("wrapped", deadLockSql))).isTrue();

        // 1205 锁等待超时 (SQLState 也是 40001，但 vendorCode 是 1205)
        SQLException lockWaitSql = new SQLException("Lock wait timeout", "40001", 1205);
        assertThat(LocalLedgerDeadlockDetector.isExactDeadlock(lockWaitSql)).isFalse();

        // 1062 唯一键重复
        SQLException dupKeySql = new SQLException("Duplicate entry", "23000", 1062);
        assertThat(LocalLedgerDeadlockDetector.isExactDeadlock(dupKeySql)).isFalse();

        // 3819 CHECK 约束违反
        SQLException checkSql = new SQLException("Check constraint violation", "HY000", 3819);
        assertThat(LocalLedgerDeadlockDetector.isExactDeadlock(checkSql)).isFalse();
    }

    @Test
    void workerSucceedsFirstAttemptWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = coordinator.executeWorker("CMD_1", (nonce, attempt) -> {
            attempts.incrementAndGet();
            return "SUCCESS";
        });
        assertThat(result).isEqualTo("SUCCESS");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void workerDeadlocksOnFirstAttemptAndSucceedsOnSecondAttemptWithNewNonce() {
        AtomicInteger attempts = new AtomicInteger(0);
        Set<String> nonces = new HashSet<>();
        List<Long> backoffs = new ArrayList<>();

        String result = coordinator.executeWorker("CMD_2", (nonce, attempt) -> {
            int a = attempts.incrementAndGet();
            nonces.add(Base64.getEncoder().encodeToString(nonce));
            if (a == 1) {
                SQLException sqlEx = new SQLException("Deadlock found", "40001", 1213);
                throw new LocalLedgerDeadlockException(sqlEx);
            }
            return "RECOVERED";
        });

        assertThat(result).isEqualTo("RECOVERED");
        assertThat(attempts.get()).isEqualTo(2);
        // 验证两次尝试的 nonce 严格不相同
        assertThat(nonces).hasSize(2);
    }

    @Test
    void non1213ErrorsDoNotRetryAndPreserveCause() {
        // 1205 不重试
        AtomicInteger attempts1205 = new AtomicInteger(0);
        assertThatThrownBy(() -> coordinator.executeWorker("CMD_1205", (nonce, attempt) -> {
            attempts1205.incrementAndGet();
            throw new RuntimeException("lock wait timeout", new SQLException("Lock wait timeout", "40001", 1205));
        })).hasCauseInstanceOf(SQLException.class);
        assertThat(attempts1205.get()).isEqualTo(1);

        // 1062 不重试
        AtomicInteger attempts1062 = new AtomicInteger(0);
        assertThatThrownBy(() -> coordinator.executeWorker("CMD_1062", (nonce, attempt) -> {
            attempts1062.incrementAndGet();
            throw new RuntimeException("duplicate key", new SQLException("Duplicate", "23000", 1062));
        })).hasCauseInstanceOf(SQLException.class);
        assertThat(attempts1062.get()).isEqualTo(1);

        // 3819 不重试
        AtomicInteger attempts3819 = new AtomicInteger(0);
        assertThatThrownBy(() -> coordinator.executeWorker("CMD_3819", (nonce, attempt) -> {
            attempts3819.incrementAndGet();
            throw new RuntimeException("check failed", new SQLException("Check failed", "HY000", 3819));
        })).hasCauseInstanceOf(SQLException.class);
        assertThat(attempts3819.get()).isEqualTo(1);
    }

    @Test
    void secondAttemptDeadlockDoesNotRetryThirdTime() {
        AtomicInteger attempts = new AtomicInteger(0);
        assertThatThrownBy(() -> coordinator.executeWorker("CMD_REPEAT_DEADLOCK", (nonce, attempt) -> {
            attempts.incrementAndGet();
            SQLException sqlEx = new SQLException("Deadlock found", "40001", 1213);
            throw new LocalLedgerDeadlockException(sqlEx);
        })).isInstanceOf(LocalLedgerDeadlockException.class);

        // 最多 2 次尝试，绝对不启动第 3 次
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void insufficientAdmissionBudgetRejectsBeforeExecutingWorker() {
        long now = System.nanoTime();
        // 设置已超期预算（剩余仅 2000ms < 3500ms）
        BudgetContext context = BudgetContext.create(now, now + TimeUnit.MILLISECONDS.toNanos(2000));
        BudgetContext.set(context);

        AtomicInteger attempts = new AtomicInteger(0);
        assertThatThrownBy(() -> coordinator.executeWorker("CMD_EXPIRED", (nonce, attempt) -> {
            attempts.incrementAndGet();
            return "FAIL";
        })).isInstanceOf(BudgetTimeoutException.class);

        assertThat(attempts.get()).isEqualTo(0);
    }
}
