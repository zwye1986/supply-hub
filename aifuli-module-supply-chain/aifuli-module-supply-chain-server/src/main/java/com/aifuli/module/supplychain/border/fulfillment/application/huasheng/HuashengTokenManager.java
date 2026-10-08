package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengProtectedFailure;
import com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpExchange;
import com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpTransportPort;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengBusinessError;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengParseFact;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengRequestBuilder;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTokenRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTokenResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTransportContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengWireRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 华昇 Token 全生命周期管理器。
 *
 * <p>职责：
 * 负责 Token 的获取（acquireToken）、缓存命中判定、DCL 双重检查、单飞并发互斥锁协调、
 * 本地 7200 秒 TTL 策略守卫、双时钟过期计算与基于 generation 的 CAS 安全失效管理。
 *
 * <p>不变量：
 * <ul>
 *   <li>仅依赖 {@link HuashengTokenCachePort} 端口，绝不反向 import 基础设施层具体缓存实现；</li>
 *   <li>相同 Identity 并发获取时，通过单飞锁确保至多发起恰好 1 次物理网络外呼；</li>
 *   <li>严格执行双时钟校验，系统时间倒退时依然受单调 Ticker 保护；</li>
 *   <li>TTL 大于 7200 秒（如 7201）在应用层拒绝为本地策略异常 {@code TOKEN_TTL_UNACCEPTABLE}；</li>
 *   <li>不懂订单、资金或 Timer 逻辑，对外仅生产受保护的 {@link HuashengInternalAttempt} 内部结果。</li>
 * </ul>
 *
 * <p>关键决策：
 * 单调 Ticker 与 Wall Clock 纳秒级对齐，网络往返耗时从有效期中精确扣除；
 * 采用不可变代次（generation）自增序列，防迟到 61 误删新 Token。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#182-复合身份材料指纹与缓存条目ad-a5-02--ad-a5-04、§8.1、§8.2。
 */
public class HuashengTokenManager {

    private final HuashengTokenCachePort cachePort;
    private final HuashengRequestBuilder requestBuilder;
    private final HuashengHttpTransportPort transportPort;
    private final Clock clock;
    private final LongSupplier nanoTicker;
    private final AtomicLong generationSequence = new AtomicLong(1L);

    /**
     * 构造 Token 管理器。
     *
     * @param cachePort 缓存端口，非空
     * @param requestBuilder 请求构造器，非空
     * @param transportPort 物理传输端口，非空
     * @param clock Wall Clock 时钟，非空
     * @param nanoTicker 单调 Ticker 时钟源，非空
     */
    public HuashengTokenManager(
            HuashengTokenCachePort cachePort,
            HuashengRequestBuilder requestBuilder,
            HuashengHttpTransportPort transportPort,
            Clock clock,
            LongSupplier nanoTicker
    ) {
        this.cachePort = Objects.requireNonNull(cachePort, "cachePort不能为null");
        this.requestBuilder = Objects.requireNonNull(requestBuilder, "requestBuilder不能为null");
        this.transportPort = Objects.requireNonNull(transportPort, "transportPort不能为null");
        this.clock = Objects.requireNonNull(clock, "clock不能为null");
        this.nanoTicker = Objects.requireNonNull(nanoTicker, "nanoTicker不能为null");
    }

    /**
     * 获取可用的 Token 缓存条目。
     *
     * <p>流程：
     * 1. 查缓存快路径：若已存在且未进入 refreshAhead 提前刷新窗口，直接复用返回；<br>
     * 2. 获取单飞锁租约：进入互斥锁；<br>
     * 3. 锁内二次检查（DCL）：若其它线程已刷新出可用 Token，直接复用返回；<br>
     * 4. 组装请求并执行物理外呼，完成响应解析；<br>
     * 5. 校验 TTL（<=0 格式非法，>7200 策略拒绝）与 Token 字符串；<br>
     * 6. 扣除耗时、分配新代次、存入缓存并返回成功条目。
     *
     * @param context 授权调用上下文，非空
     * @return 包含可用 {@link HuashengTokenEntry} 或失败受保护因果的尝试结果
     */
    public HuashengInternalAttempt<HuashengTokenEntry> acquireToken(HuashengAuthorizedCallContext context) {
        Objects.requireNonNull(context, "context不能为null");

        HuashengTokenIdentity identity = context.toTokenIdentity();

        // 1. 快路径查询缓存
        Optional<HuashengTokenEntry> cached = cachePort.get(identity);
        if (cached.isPresent() && cached.get().isUsable(clock, nanoTicker)) {
            return HuashengInternalAttempt.parsed(cached.get());
        }

        // 2. 慢路径：获取单飞锁执行受控刷新
        return cachePort.withIdentityLease(identity, () -> {
            // 3. DCL 二次检查
            Optional<HuashengTokenEntry> doubleChecked = cachePort.get(identity);
            if (doubleChecked.isPresent() && doubleChecked.get().isUsable(clock, nanoTicker)) {
                return HuashengInternalAttempt.parsed(doubleChecked.get());
            }

            // 4. 执行物理 TOKEN 网络通信
            Instant startInstant = clock.instant();
            long startNanos = nanoTicker.getAsLong();

            HuashengTokenRequest tokenRequest;
            HuashengWireRequest wireRequest;
            try {
                tokenRequest = new HuashengTokenRequest(
                        context.callContext().appId(),
                        context.callContext().credentialMaterial().appSecret()
                );
                wireRequest = requestBuilder.build(
                        HuashengEndpoint.TOKEN,
                        tokenRequest,
                        context.callContext(),
                        Optional.empty()
                );
            } catch (RuntimeException e) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.PREFLIGHT,
                        e,
                        Optional.empty()
                ));
            }

            long tokenDeadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(
                    Math.max(1L, (long) (context.callContext().connectTimeoutMs() + context.callContext().readTimeoutMs()) / 1000L));
            HuashengTransportContext transportContext = HuashengTransportContext.from(
                    context.callContext(),
                    tokenDeadlineNanos,
                    1
            );

            HuashengInternalAttempt<HuashengHttpExchange> exchangeAttempt =
                    transportPort.exchange(wireRequest, transportContext);

            if (exchangeAttempt instanceof HuashengInternalAttempt.Failed<HuashengHttpExchange> failedExchange) {
                return HuashengInternalAttempt.failed(failedExchange.failure());
            }

            HuashengHttpExchange exchange =
                    ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) exchangeAttempt).value();

            HuashengInternalAttempt<HuashengParseFact<HuashengTokenResponse>> parseAttempt = exchange.parseToken();
            if (parseAttempt instanceof HuashengInternalAttempt.Failed<HuashengParseFact<HuashengTokenResponse>> failedParse) {
                return HuashengInternalAttempt.failed(failedParse.failure());
            }

            HuashengParseFact<HuashengTokenResponse> fact =
                    ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengTokenResponse>>) parseAttempt).value();

            if (fact instanceof HuashengParseFact.Business<HuashengTokenResponse> businessFact) {
                HuashengBusinessError businessError = businessFact.error();
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.PREFLIGHT,
                        new IllegalStateException("TOKEN端点业务拒绝: " + businessError.rawCode()),
                        Optional.of(exchange.evidence())
                ));
            }

            HuashengTokenResponse tokenResponse = ((HuashengParseFact.Data<HuashengTokenResponse>) fact).data();
            long ttlSeconds = tokenResponse.expiresInSeconds();

            // 5. TTL 校验
            if (ttlSeconds <= 0L) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.PREFLIGHT,
                        new IllegalArgumentException("Token TTL 必须为正整数: " + ttlSeconds),
                        Optional.of(exchange.evidence())
                ));
            }

            if (!HuashengTokenPolicy.isTtlAcceptable(ttlSeconds)) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.PREFLIGHT,
                        new IllegalStateException(HuashengTokenPolicy.ERROR_TOKEN_TTL_UNACCEPTABLE + ": " + ttlSeconds),
                        Optional.of(exchange.evidence())
                ));
            }

            try {
                HuashengTokenPolicy.validateAccessToken(tokenResponse.accessToken());
            } catch (RuntimeException e) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.PREFLIGHT,
                        e,
                        Optional.of(exchange.evidence())
                ));
            }

            // 6. 耗时扣除与有效期计算
            Instant endInstant = clock.instant();
            long endNanos = nanoTicker.getAsLong();
            long elapsedSeconds = Math.max(0L, Duration.between(startInstant, endInstant).toSeconds());

            long remainingTtlSeconds = ttlSeconds - elapsedSeconds;
            if (remainingTtlSeconds <= 0L) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.PREFLIGHT,
                        new IllegalStateException("Token在网络传输后已耗尽有效期"),
                        Optional.of(exchange.evidence())
                ));
            }

            Instant expiresAt = startInstant.plusSeconds(ttlSeconds);
            long leaseDeadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(ttlSeconds);
            long refreshAheadSeconds = HuashengTokenPolicy.calculateRefreshAheadSeconds(ttlSeconds);

            long generation = generationSequence.getAndIncrement();
            HuashengTokenEntry newEntry = new HuashengTokenEntry(
                    tokenResponse.accessToken(),
                    generation,
                    expiresAt,
                    leaseDeadlineNanos,
                    refreshAheadSeconds,
                    Optional.of(exchange.evidence())
            );

            if (!newEntry.isUsable(clock, nanoTicker)) {
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.PREFLIGHT,
                        new IllegalStateException("Token刚获取即处于不可用状态"),
                        Optional.of(exchange.evidence())
                ));
            }

            cachePort.put(identity, newEntry);
            return HuashengInternalAttempt.parsed(newEntry);
        });
    }

    /**
     * 基于 CAS generation 条件失效 Token 缓存。
     *
     * @param identity 复合身份主键
     * @param generation 触发失效时的代次编号
     * @return 若成功移除返回 true，代次不匹配返回 false
     */
    public boolean invalidateIfGeneration(HuashengTokenIdentity identity, long generation) {
        return cachePort.invalidateIfGeneration(identity, generation);
    }
}
