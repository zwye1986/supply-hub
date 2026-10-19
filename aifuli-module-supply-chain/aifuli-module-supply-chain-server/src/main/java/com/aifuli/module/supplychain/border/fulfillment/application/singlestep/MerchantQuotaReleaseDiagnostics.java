package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Runner终结并发回收故障的唯一诊断边界：首次安全因果、持续计数、真实释放后的恢复。
 * 固定一个supplyChainDataSource/RELEASE_FAILED故障桶，不保存订单、商户或异常对象；内存不随业务量增长。
 * 同步只覆盖短暂计数与日志投递，不持数据库锁、不调原单、不创建线程或重试发送资格。
 * 设计：docs/superpowers/specs/2026-10-18-single-step-merchant-quota-design.md#64-释放故障注入与诊断收敛2026-10-19补充。
 */
@Slf4j
final class MerchantQuotaReleaseDiagnostics {
    private static final long WINDOW_NANOS = Duration.ofSeconds(60).toNanos();
    private final LongSupplier nanoTime;
    private boolean failing;
    private long startedAt;
    private long lastReportAt;
    private long totalCount;
    private long pendingCount;
    private long suppressedCount;

    MerchantQuotaReleaseDiagnostics(LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    /** 首因在此降级终结，后续窗口仅计数；所有异常仍沿原安全渲染，不记录异常原文或SQL。 */
    synchronized void failed(String operationNo, long generation, RuntimeException failure) {
        long now = nanoTime.getAsLong();
        if (!failing) {
            failing = true;
            startedAt = lastReportAt = now;
            totalCount = 1;
            pendingCount = suppressedCount = 0;
            log.error("[merchantQuotaRelease][商户调用占用回收降级] operationNo={}, generation={}, windowMs={}, totalCount={}, suppressedCount={}",
                    operationNo, generation, 0L, totalCount, suppressedCount, SingleStepFulfillmentRunner.safeFailure(failure, 0));
            return;
        }
        totalCount++;
        if (now - lastReportAt >= WINDOW_NANOS) {
            // 当前事件输出汇总；pendingCount是上次报告后确实被抑制的事件，不含当前报告本身。
            log.warn("[merchantQuotaRelease][商户调用占用回收持续降级] operationNo={}, generation={}, windowMs={}, totalCount={}, suppressedCount={}",
                    operationNo, generation, elapsedMs(now, lastReportAt), pendingCount + 1, pendingCount);
            lastReportAt = now;
            pendingCount = 0;
        } else {
            pendingCount++;
            suppressedCount++;
        }
    }

    /**
     * 仅在后续真实租约释放事务提交后调用，不宣称旧失败租约已补回收，旧占用仍靠自身到期兜底。
     * 空租约幂等跳过、资金成功和租约过期均不冒充数据库回收恢复；设计见本类链接§6.4。
     */
    synchronized void recovered(String operationNo, long generation) {
        if (!failing) return;
        log.info("[merchantQuotaReleaseRecovered][商户调用占用回收恢复] operationNo={}, generation={}, windowMs={}, totalCount={}, suppressedCount={}",
                operationNo, generation, elapsedMs(nanoTime.getAsLong(), startedAt), totalCount, suppressedCount);
        failing = false;
        totalCount = pendingCount = suppressedCount = 0;
    }

    private static long elapsedMs(long now, long since) { return Duration.ofNanos(now - since).toMillis(); }
}
