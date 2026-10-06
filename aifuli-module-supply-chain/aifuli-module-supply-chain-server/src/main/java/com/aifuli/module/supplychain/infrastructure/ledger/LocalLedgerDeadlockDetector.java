package com.aifuli.module.supplychain.infrastructure.ledger;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 依据 V4A 获审规范：严格判定同一个 SQLException 同时满足 vendorCode == 1213 AND SQLState.equals("40001")。
 * 遍历 Throwable cause 与 SQLException nextException 完整链，防止环形链。
 * 1205/40001、1062/23000、3819/HY000 及其它错误绝对判定为 false。
 */
public final class LocalLedgerDeadlockDetector {

    private LocalLedgerDeadlockDetector() {}

    public static boolean isExactDeadlock(Throwable throwable) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return walk(throwable, seen);
    }

    private static boolean walk(Throwable t, Set<Throwable> seen) {
        if (t == null || !seen.add(t)) {
            return false;
        }
        if (t instanceof SQLException s) {
            if (s.getErrorCode() == 1213 && "40001".equals(s.getSQLState())) {
                return true;
            }
            if (walk(s.getNextException(), seen)) {
                return true;
            }
        }
        for (Throwable suppressed : t.getSuppressed()) {
            if (walk(suppressed, seen)) {
                return true;
            }
        }
        return walk(t.getCause(), seen);
    }
}
