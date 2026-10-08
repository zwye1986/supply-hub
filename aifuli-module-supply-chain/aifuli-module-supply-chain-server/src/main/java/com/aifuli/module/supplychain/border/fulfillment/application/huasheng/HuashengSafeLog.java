package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 华昇应用层安全日志服务。
 *
 * <p>职责：
 * 正常通信入口记录 debug 日志（脱敏身份与操作标识）；
 * 异常记录仅接收 {@link HuashengFailureDiagnostics} 安全诊断对象（绝无 Throwable 重载），
 * 并按 {@code endpoint + failureKind + rawCode} 进行分钟桶聚合限频，
 * 同类每分钟仅输出首条 WARN 日志与上一分钟抑制计数，避免日志风暴。
 *
 * <p>不变量：
 * <ul>
 *   <li>聚合键容量上限恒为 1024 键；超出时归入固定 {@code OVERFLOW} 桶，杜绝内存膨胀；</li>
 *   <li>闲置超过 2 分钟的聚合键惰性退休清理；</li>
 *   <li>绝不输出 Token、密钥、密码、卡密、明文手机号或未经脱敏的原始单号。</li>
 * </ul>
 *
 * <p>关键决策：
 * 不调用 logger 的任何接收 {@link Throwable} 参数的重载方法，彻底阻断原始敏感异常因果链打印到日志文件。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#9-脱敏日志因果与资源、§18.4。
 */
public class HuashengSafeLog {

    private static final Logger log = LoggerFactory.getLogger(HuashengSafeLog.class);
    private static final String OVERFLOW_KEY = "OVERFLOW";

    private static final class BucketEntry {
        volatile long minute;
        final AtomicLong suppressedCount = new AtomicLong(0L);

        BucketEntry(long minute) {
            this.minute = minute;
        }
    }

    private final Clock clock;
    private final int maxKeys;
    private final ConcurrentHashMap<String, BucketEntry> buckets = new ConcurrentHashMap<>();

    /**
     * 默认构造函数（使用系统 UTC 时钟与 1024 键上限）。
     */
    public HuashengSafeLog() {
        this(Clock.systemUTC(), 1024);
    }

    /**
     * 可注入时钟与容量上限的构造函数（供测试验证限频与退休）。
     *
     * @param clock 时钟源，非空
     * @param maxKeys 聚合键容量上限，大于 0
     */
    public HuashengSafeLog(Clock clock, int maxKeys) {
        this.clock = Objects.requireNonNull(clock, "clock不能为null");
        if (maxKeys <= 0) {
            throw new IllegalArgumentException("maxKeys必须为正整数: " + maxKeys);
        }
        this.maxKeys = maxKeys;
    }

    /**
     * 记录正常通信入口日志（debug 级别）。
     *
     * @param operationNo 操作序号
     * @param connectorVersionId 连接器版本 ID
     * @param endpoint 通信端点
     * @param traceId 链路追踪标识
     */
    public void recordEntry(String operationNo, Long connectorVersionId, HuashengEndpoint endpoint, String traceId) {
        if (log.isDebugEnabled()) {
            log.debug("[HUA_SHENG 入口] opNo={}, verId={}, endpoint={}, traceId={}",
                    operationNo, connectorVersionId, endpoint, traceId);
        }
    }

    /**
     * 记录协议失败安全日志（warn 级别，分钟桶聚合限频）。
     *
     * @param diagnostics 单向安全诊断对象，非空
     */
    public void recordFailure(HuashengFailureDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics不能为null");

        HuashengEndpoint endpoint = diagnostics.evidence()
                .map(HuashengExchangeEvidence::endpoint)
                .orElse(HuashengEndpoint.PLACE);

        OptionalInt rawCode = diagnostics.evidence()
                .flatMap(e -> e.rawBusinessCode().isPresent() ? java.util.Optional.of(e.rawBusinessCode()) : java.util.Optional.empty())
                .orElse(OptionalInt.empty());

        long currentMinute = clock.instant().toEpochMilli() / 60_000L;
        retireIdleBuckets(currentMinute);

        String key = buildKey(endpoint, diagnostics.failureKind(), rawCode);
        if (!buckets.containsKey(key) && buckets.size() >= maxKeys) {
            key = OVERFLOW_KEY;
        }

        final String targetKey = key;
        BucketEntry entry = buckets.computeIfAbsent(targetKey, k -> new BucketEntry(-1L));

        boolean isFirstInMinute = false;
        long prevSuppressed = 0L;
        synchronized (entry) {
            if (entry.minute != currentMinute) {
                isFirstInMinute = true;
                prevSuppressed = entry.suppressedCount.getAndSet(0L);
                entry.minute = currentMinute;
            } else {
                entry.suppressedCount.incrementAndGet();
            }
        }
        if (isFirstInMinute) {
            log.warn("[HUA_SHENG 失败] endpoint={}, kind={}, reason={}, prevMinuteSuppressed={}",
                    endpoint, diagnostics.failureKind(), diagnostics.reasonCode(), prevSuppressed);
        }
    }

    /**
     * 获取当前处于活跃状态的聚合键总数。
     *
     * @return 活跃键数量
     */
    public int activeKeyCount() {
        return buckets.size();
    }

    /**
     * 获取指定维度的当前被抑制次数（供契约测试断言）。
     *
     * @param endpoint 端点
     * @param kind 失败类别
     * @param rawCode 业务错误码容器
     * @return 抑制计数
     */
    public long getSuppressedCount(HuashengEndpoint endpoint, HuashengFailureKind kind, OptionalInt rawCode) {
        String key = buildKey(endpoint, kind, rawCode);
        BucketEntry entry = buckets.get(key);
        if (entry == null) {
            return 0L;
        }
        return entry.suppressedCount.get();
    }

    private String buildKey(HuashengEndpoint endpoint, HuashengFailureKind kind, OptionalInt rawCode) {
        return endpoint.name() + ":" + kind.name() + ":" + (rawCode.isPresent() ? rawCode.getAsInt() : "none");
    }

    private void retireIdleBuckets(long currentMinute) {
        Iterator<Map.Entry<String, BucketEntry>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BucketEntry> e = it.next();
            if (currentMinute - e.getValue().minute >= 2) {
                it.remove();
            }
        }
    }
}
