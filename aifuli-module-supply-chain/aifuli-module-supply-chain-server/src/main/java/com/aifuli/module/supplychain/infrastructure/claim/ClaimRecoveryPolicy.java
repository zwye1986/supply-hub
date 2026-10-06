package com.aifuli.module.supplychain.infrastructure.claim;

/**
 * 领取回收共用技术原因，避免两类源的持久诊断码漂移。
 * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算。
 */
public final class ClaimRecoveryPolicy {
    public static final String BUDGET_EXHAUSTED = "CLAIM_BUDGET_EXHAUSTED";
    public static final String ACTIVE_LEASE_MISSING = "CLAIM_ACTIVE_LEASE_MISSING";
    public static final int DIAGNOSTIC_SAMPLE_SIZE = 8;
    private static final long DIAGNOSTIC_INTERVAL_NANOS = java.time.Duration.ofMinutes(1).toNanos();

    /**
     * 固定单槽限频而非逐id缓存，损坏行再多也不扩内存；单调时钟只控制诊断。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算。
     */
    public static final class DiagnosticGate {
        private final java.util.concurrent.atomic.AtomicLong last =
                new java.util.concurrent.atomic.AtomicLong(System.nanoTime() - DIAGNOSTIC_INTERVAL_NANOS);

        public boolean acquire() {
            long previous = last.get();
            long now = System.nanoTime();
            return now - previous >= DIAGNOSTIC_INTERVAL_NANOS && last.compareAndSet(previous, now);
        }
    }
    private ClaimRecoveryPolicy() { }
}
