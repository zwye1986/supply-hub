package com.aifuli.module.supplychain.infrastructure.configuration;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 纯本地工作者事务执行预算上下文。
 * 依据 R01 / R02 获审设计：
 * - 单调时间起点 t0，命令绝对硬预算 commandDeadline = t0 + 15s；
 * - 清理预留 C = 1000ms，最小 SQL 执行预算 S = 1000ms，准入门槛 R >= 3500ms；
 * - floor((R - C) / 1000ms) >= 1；
 * - 跟踪线程退出清理状态，清理失败严格禁止重试。
 */
public class BudgetContext {

    private static final ThreadLocal<BudgetContext> CURRENT = new ThreadLocal<>();

    public static final long TOTAL_COMMAND_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    public static final long CLEANUP_RESERVE_NANOS = TimeUnit.MILLISECONDS.toNanos(1000);
    public static final long MIN_SQL_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(1000);
    public static final long ADMISSION_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(3500);

    private final long t0;
    private final long commandDeadline;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean cleanupFailed = new AtomicBoolean(false);

    private BudgetContext(long t0, long commandDeadline) {
        this.t0 = t0;
        this.commandDeadline = commandDeadline;
    }

    public static BudgetContext create(long t0, long commandDeadline) {
        return new BudgetContext(t0, commandDeadline);
    }

    public static BudgetContext current() {
        return CURRENT.get();
    }

    public static void set(BudgetContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public long getT0() {
        return t0;
    }

    public long getCommandDeadline() {
        return commandDeadline;
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCleanupFailed() {
        return cleanupFailed.get();
    }

    public void markCleanupFailed() {
        cleanupFailed.set(true);
    }

    public long remainingNanos() {
        return commandDeadline - System.nanoTime();
    }

    public long remainingMillis() {
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos());
    }

    public void checkAdmission() {
        if (isCancelled()) {
            throw new BudgetTimeoutException("工作者已被取消或中断");
        }
        long r = remainingNanos();
        if (r < ADMISSION_THRESHOLD_NANOS) {
            throw new BudgetTimeoutException("预算不足准入门槛 3500ms: 剩余 " + TimeUnit.NANOSECONDS.toMillis(r) + "ms");
        }
        long floorSeconds = (long) Math.floor((r - CLEANUP_RESERVE_NANOS) / 1_000_000_000.0);
        if (floorSeconds < 1) {
            throw new BudgetTimeoutException("向下取整可用执行预算不足 1 秒: 剩余 " + TimeUnit.NANOSECONDS.toMillis(r) + "ms");
        }
    }
}
