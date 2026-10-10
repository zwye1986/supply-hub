package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 扫描局部诊断限频：查询／刷新与业务／技术形成四个封闭桶，不使用目录号或异常类建桶。
 * 首次保留因果，60秒窗口汇总计数，完整恢复后清桶；计数不替代 SweepBatch 的真实处理结果。
 * 仅单线程扫描入口使用，见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#31-2026-10-10-评审修复边界用户确认方案-3。
 */
final class BuyerCatalogSweepFailureLog {
    enum Stage { QUERY, REFRESH }
    private enum Kind { BUSINESS, TECHNICAL }
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);
    private final EnumMap<Stage, EnumMap<Kind, Bucket>> buckets = new EnumMap<>(Stage.class);
    private final LongSupplier nanoTime;

    BuyerCatalogSweepFailureLog() {
        this(System::nanoTime);
    }

    BuyerCatalogSweepFailureLog(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    void failure(Logger log, Stage stage, Long listingId, RuntimeException failure) {
        Kind kind = failure instanceof ServiceException ? Kind.BUSINESS : Kind.TECHNICAL;
        var stageBuckets = buckets.computeIfAbsent(stage, ignored -> new EnumMap<>(Kind.class));
        long now = nanoTime.getAsLong();
        Bucket bucket = stageBuckets.get(kind);
        if (bucket == null) {
            stageBuckets.put(kind, new Bucket(now));
            if (failure instanceof ServiceException business) {
                log.warn("[catalogSweep][扫描业务拒绝] stage={}, listingId={}, errorCode={}", stage, listingId, business.getCode());
            } else {
                log.warn("[catalogSweep][扫描可恢复故障] stage={}, listingId={}", stage, listingId, failure);
            }
            return;
        }
        bucket.totalCount++;
        bucket.suppressedCount++;
        if (now - bucket.startedNanos >= WINDOW_NANOS) {
            log.warn("[catalogSweep][持续故障汇总] stage={}, kind={}, windowMs={}, totalCount={}, suppressedCount={}",
                    stage, kind, TimeUnit.NANOSECONDS.toMillis(now - bucket.startedNanos),
                    bucket.totalCount, bucket.suppressedCount);
            bucket.startedNanos = now;
            bucket.suppressedCount = 0;
        }
    }

    void recovered(Logger log, Stage stage) {
        var stageBuckets = buckets.remove(stage);
        if (stageBuckets != null) {
            stageBuckets.forEach((kind, bucket) -> log.info(
                    "[catalogSweep][扫描故障恢复] stage={}, kind={}, totalCount={}, suppressedCount={}",
                    stage, kind, bucket.totalCount, bucket.suppressedCount));
        }
    }

    private static final class Bucket {
        private long startedNanos;
        private long totalCount = 1;
        private long suppressedCount;

        private Bucket(long startedNanos) {
            this.startedNanos = startedNanos;
        }
    }
}
