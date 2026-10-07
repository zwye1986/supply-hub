package com.aifuli.module.supplychain.supplieradapter.huasheng.http;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 华昇 HTTP 传输安全日志记录器。
 *
 * <p>职责：
 * 纯包私有安全日志门面，统一记录 HTTP 请求发起、响应完成与失败诊断。
 * 杜绝打印明文请求 URL、WireRequest、请求头、Token、签名、请求正文、密码或异常堆栈文本；
 * 针对高频失败提供单分钟首 Warn 报警与前窗抑制计数，防日志风暴。
 *
 * <p>不变量：
 * <ul>
 *   <li>无后台线程，无定时调度器，所有退休与清理均在调用线程惰性执行；</li>
 *   <li>总内存键数上限严格限制为 1024（包含固定溢出桶），满额不再创建新键；</li>
 *   <li>闲置超过 2 分钟的桶在写入时惰性退休并释放内存；</li>
 *   <li>时间源通过单调时钟 {@link LongSupplier} 获取，不受系统时钟回拨干扰；</li>
 *   <li>operationNo 与 traceId 等非可信标识一律固定掩码输出。</li>
 * </ul>
 *
 * <p>关键决策：
 * 失败日志不传递 Throwable 实例至日志框架，仅解构 {@link HuashengFailureDiagnostics} 的安全枚举；
 * A4 传输层 rawCode 恒为空，正常业务键集合极小，溢出桶作为恶意攻击防护兜底。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#177-a4-安全日志tls-与架构负控制。
 */
final class HuashengHttpSafeLog {

    private static final Logger log = LoggerFactory.getLogger(HuashengHttpSafeLog.class);
    private static final int MAX_KEYS = 1024;
    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private static final BucketKey OVERFLOW_KEY = new BucketKey("__OVERFLOW__", "__OVERFLOW__", "");

    private final LongSupplier monotonicTicker;
    private final ConcurrentHashMap<BucketKey, BucketEntry> buckets = new ConcurrentHashMap<>();

    /**
     * 默认构造器，使用系统纳秒单调时钟。
     */
    HuashengHttpSafeLog() {
        this(System::nanoTime);
    }

    /**
     * 包内测试构造器，允许注入受控单调时钟。
     *
     * @param monotonicTicker 单调纳秒时钟源，非空
     */
    HuashengHttpSafeLog(LongSupplier monotonicTicker) {
        this.monotonicTicker = Objects.requireNonNull(monotonicTicker, "monotonicTicker不能为null");
        this.buckets.put(OVERFLOW_KEY, new BucketEntry(0L));
    }

    /**
     * 记录请求开始（DEBUG 级别）。
     *
     * @param context 传输上下文，可为 null（防御）
     * @param endpoint 请求端点枚举，可为 null
     */
    void recordStart(HuashengTransportContext context, HuashengEndpoint endpoint) {
        if (!log.isDebugEnabled()) {
            return;
        }
        String endpointName = endpoint != null ? endpoint.name() : "UNKNOWN";
        long connectorVersionId = context != null ? context.connectorVersionId() : 0L;
        int attemptOrdinal = context != null ? context.transportAttemptOrdinal() : 0;
        String opNo = context != null ? mask(context.operationNo()) : "UNKNOWN";
        String trace = context != null ? mask(context.traceId()) : "UNKNOWN";

        if (endpoint == HuashengEndpoint.TOKEN) {
            log.debug("华昇HTTP传输开始: endpoint={}, connectorVersionId={}, attempt={}, operationNo={}, traceId={}",
                    endpointName, connectorVersionId, attemptOrdinal, opNo, trace);
        } else {
            log.debug("华昇HTTP传输开始: endpoint={}, connectorVersionId={}, attempt={}, operationNo={}, traceId={}, quantity=1",
                    endpointName, connectorVersionId, attemptOrdinal, opNo, trace);
        }
    }

    /**
     * 记录请求成功结果（DEBUG 级别）。
     *
     * @param context 传输上下文，可为 null
     * @param evidence 通信证据，非空
     */
    void recordResult(HuashengTransportContext context, HuashengExchangeEvidence evidence) {
        if (!log.isDebugEnabled()) {
            return;
        }
        String endpointName = evidence.endpoint() != null ? evidence.endpoint().name() : "UNKNOWN";
        long connectorVersionId = context != null ? context.connectorVersionId() : 0L;
        int attemptOrdinal = context != null ? context.transportAttemptOrdinal() : 0;
        String opNo = context != null ? mask(context.operationNo()) : "UNKNOWN";
        String trace = context != null ? mask(context.traceId()) : "UNKNOWN";
        String status = evidence.httpStatus().isPresent() ? String.valueOf(evidence.httpStatus().getAsInt()) : "NONE";

        log.debug("华昇HTTP传输成功: endpoint={}, connectorVersionId={}, attempt={}, operationNo={}, traceId={}, httpStatus={}, durationMs={}, requestBytes={}, responseBytes={}, physicalSendCount={}",
                endpointName, connectorVersionId, attemptOrdinal, opNo, trace, status,
                evidence.durationMs(), evidence.requestBytes(), evidence.responseBytes(), evidence.physicalSendCount());
    }

    /**
     * 记录请求失败（WARN 级别，按分钟桶限频抑制）。
     *
     * @param context 传输上下文，可为 null
     * @param diagnostics 安全诊断值对象，非空
     */
    void recordFailure(HuashengTransportContext context, HuashengFailureDiagnostics diagnostics) {
        String endpointName = "UNKNOWN";
        if (diagnostics != null && diagnostics.evidence().isPresent()) {
            HuashengEndpoint ep = diagnostics.evidence().get().endpoint();
            if (ep != null) {
                endpointName = ep.name();
            }
        }
        String kindName = diagnostics != null && diagnostics.failureKind() != null
                ? diagnostics.failureKind().name() : "UNKNOWN";
        String reasonName = diagnostics != null && diagnostics.reasonCode() != null
                ? diagnostics.reasonCode().name() : "UNKNOWN";
        String causeName = diagnostics != null && diagnostics.causeKind() != null
                ? diagnostics.causeKind().name() : "UNKNOWN";

        BucketKey key = new BucketKey(endpointName, kindName, "");
        RateLimitDecision decision = evaluateRateLimit(key);

        if (decision.shouldLogWarn) {
            long connectorVersionId = context != null ? context.connectorVersionId() : 0L;
            int attemptOrdinal = context != null ? context.transportAttemptOrdinal() : 0;
            String opNo = context != null ? mask(context.operationNo()) : "UNKNOWN";
            String trace = context != null ? mask(context.traceId()) : "UNKNOWN";

            log.warn("华昇HTTP传输失败: endpoint={}, failureKind={}, reasonCode={}, causeKind={}, connectorVersionId={}, attempt={}, operationNo={}, traceId={}, suppressedPrevious={}",
                    endpointName, kindName, reasonName, causeName,
                    connectorVersionId, attemptOrdinal, opNo, trace, decision.suppressedInPreviousWindow);
        }
    }

    /**
     * 包私有合成失败注入入口，仅供容量上限 1024 与溢出桶测试使用。
     *
     * @param syntheticEndpoint 合成端点名
     * @param syntheticKind 合成失败类别名
     * @param syntheticRawCode 合成业务码
     */
    void recordSyntheticFailureForCapacityTest(String syntheticEndpoint, String syntheticKind, String syntheticRawCode) {
        BucketKey key = new BucketKey(syntheticEndpoint, syntheticKind, syntheticRawCode != null ? syntheticRawCode : "");
        evaluateRateLimit(key);
    }

    int currentBucketCountForInspection() {
        return buckets.size();
    }

    private RateLimitDecision evaluateRateLimit(BucketKey key) {
        long currentNanos = monotonicTicker.getAsLong();
        long currentMinute = Math.floorDiv(currentNanos, NANOS_PER_MINUTE);

        // 惰性清理：当桶容量超过 512 时触发闲置清理（闲置 >= 2 分钟）
        if (buckets.size() >= 512) {
            buckets.entrySet().removeIf(entry -> {
                BucketKey k = entry.getKey();
                if (k.equals(OVERFLOW_KEY)) {
                    return false;
                }
                BucketEntry b = entry.getValue();
                synchronized (b) {
                    return (currentMinute - b.minute >= 2);
                }
            });
        }

        BucketEntry bucket = buckets.compute(key, (k, existing) -> {
            if (existing != null) {
                return existing;
            }
            if (buckets.size() >= MAX_KEYS) {
                return null; // 超过 1024 上限，映射到溢出桶
            }
            return new BucketEntry(currentMinute);
        });

        if (bucket == null) {
            bucket = buckets.computeIfAbsent(OVERFLOW_KEY, k -> new BucketEntry(currentMinute));
        }

        synchronized (bucket) {
            if (bucket.minute != currentMinute) {
                // 跨分钟新窗口：发送首 Warn 并报告前窗抑制数
                int prevSuppressed = bucket.suppressedCount;
                bucket.minute = currentMinute;
                bucket.suppressedCount = 0;
                bucket.firstWarnSent = true;
                return new RateLimitDecision(true, prevSuppressed);
            } else {
                // 同一分钟窗口
                if (!bucket.firstWarnSent) {
                    bucket.firstWarnSent = true;
                    return new RateLimitDecision(true, 0);
                } else {
                    bucket.suppressedCount++;
                    return new RateLimitDecision(false, 0);
                }
            }
        }
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return "***";
    }

    private record BucketKey(String endpoint, String failureKind, String rawCode) {}

    private static final class BucketEntry {
        long minute;
        boolean firstWarnSent;
        int suppressedCount;

        BucketEntry(long minute) {
            this.minute = minute;
            this.firstWarnSent = false;
            this.suppressedCount = 0;
        }
    }

    private record RateLimitDecision(boolean shouldLogWarn, int suppressedInPreviousWindow) {}
}
