package com.aifuli.module.supplychain.infrastructure.identity;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 场景协调器的事务外守卫及可信竞争回放；只识别MySQL指定永久业务键，编号键等其它冲突不得回放。 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#53-o2o-与-instore}。
 */
public final class OrderCreationSupport {
    private OrderCreationSupport() {}

    public static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("订单创建协调必须在实际事务外调用");
    }

    public static boolean isKnownBusinessKey(Throwable failure, String... names) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof SQLException sql)
                    || sql.getErrorCode() != 1062
                    || !"23000".equals(sql.getSQLState())) continue;
            for (String name : names) {
                Pattern exact =
                        Pattern.compile(
                                "for key ['`](?:[^'`]*\\.)?" + Pattern.quote(name) + "['`]");
                if (sql.getMessage() != null && exact.matcher(sql.getMessage()).find()) return true;
            }
        }
        return false;
    }

    /** 取号失败后最多探测一次可信已提交结果；探测也失败时保留最初系统异常与因果。 */
    public static <T> T replayAfterFailure(Supplier<T> probe, RuntimeException original) {
        try {
            return probe.get();
        } catch (RuntimeException failedRead) {
            if (original != failedRead) original.addSuppressed(failedRead);
            return null;
        }
    }
}
