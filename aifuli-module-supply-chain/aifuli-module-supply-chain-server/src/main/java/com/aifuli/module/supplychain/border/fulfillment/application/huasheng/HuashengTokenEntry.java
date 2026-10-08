package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 华昇 Token 不可变缓存条目。
 *
 * <p>职责：
 * 封装已成功获取的 Access Token 密文字符串、CAS 代次号、Wall Clock 过期时刻与单调 Ticker 租约截止点；
 * 提供严格的双时钟状态校验方法，防止时钟回拨延长 token 有效性。
 *
 * <p>不变量：
 * <ul>
 *   <li>accessToken 必须经过 {@link HuashengTokenPolicy#validateAccessToken(String)} 校验；</li>
 *   <li>generation 必须为非负长整数；</li>
 *   <li>expiresAt 必须非空；</li>
 *   <li>leaseDeadlineNanos 必须为有效单调纳秒；</li>
 *   <li>refreshAheadSeconds 必须非负；</li>
 *   <li>{@link #toString()} 固定脱敏返回字面量 {@code "HuashengTokenEntry[redacted]"}；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)。</li>
 * </ul>
 *
 * <p>关键决策：
 * 双时钟并行防护：同时校验 {@code clock.instant()} 与 {@code nanoTicker.getAsLong()}；
 * 只要任一时钟判定过期或进入 refreshAhead 提前刷新窗口，即视为不可用或已过期。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#182-复合身份材料指纹与缓存条目ad-a5-02--ad-a5-04。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengTokenEntry {

    private final String accessToken;
    private final long generation;
    private final Instant expiresAt;
    private final long leaseDeadlineNanos;
    private final long refreshAheadSeconds;
    private final Optional<HuashengExchangeEvidence> tokenEvidence;

    /**
     * 构造 Token 缓存条目（完整构造器）。
     *
     * @param accessToken 访问令牌字符串，必须合法
     * @param generation 代次编号
     * @param expiresAt Wall Clock 绝对过期时刻，非空
     * @param leaseDeadlineNanos 单调 Ticker 绝对截止纳秒
     * @param refreshAheadSeconds 提前刷新时间窗口秒数，非负
     * @param tokenEvidence 获取该 Token 时的物理通信证据容器，非空
     */
    public HuashengTokenEntry(
            String accessToken,
            long generation,
            Instant expiresAt,
            long leaseDeadlineNanos,
            long refreshAheadSeconds,
            Optional<HuashengExchangeEvidence> tokenEvidence
    ) {
        HuashengTokenPolicy.validateAccessToken(accessToken);
        if (generation < 0L) {
            throw new IllegalArgumentException("generation不能为负数");
        }
        this.accessToken = accessToken;
        this.generation = generation;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt不能为null");
        this.leaseDeadlineNanos = leaseDeadlineNanos;
        if (refreshAheadSeconds < 0L) {
            throw new IllegalArgumentException("refreshAheadSeconds不能为负数");
        }
        this.refreshAheadSeconds = refreshAheadSeconds;
        this.tokenEvidence = Objects.requireNonNull(tokenEvidence, "tokenEvidence不能为null");
    }

    /**
     * 构造 Token 缓存条目（无证据重载）。
     */
    public HuashengTokenEntry(
            String accessToken,
            long generation,
            Instant expiresAt,
            long leaseDeadlineNanos,
            long refreshAheadSeconds
    ) {
        this(accessToken, generation, expiresAt, leaseDeadlineNanos, refreshAheadSeconds, Optional.empty());
    }

    /**
     * 构造 Token 缓存条目（默认提前刷新秒数为 0）。
     */
    public HuashengTokenEntry(
            String accessToken,
            long generation,
            Instant expiresAt,
            long leaseDeadlineNanos
    ) {
        this(accessToken, generation, expiresAt, leaseDeadlineNanos, 0L, Optional.empty());
    }

    /**
     * 检查当前 Token 是否处于可用状态（未进入 refreshAhead 提前刷新窗口且单调租约未超期）。
     *
     * @param clock Wall Clock 时钟，非空
     * @param nanoTicker 单调纳秒时钟源，非空
     * @return 若可用返回 true，否则返回 false
     */
    public boolean isUsable(Clock clock, LongSupplier nanoTicker) {
        Objects.requireNonNull(clock, "clock不能为null");
        Objects.requireNonNull(nanoTicker, "nanoTicker不能为null");

        Instant usableUntilWall = expiresAt.minusSeconds(refreshAheadSeconds);
        long usableUntilNanos = leaseDeadlineNanos - TimeUnit.SECONDS.toNanos(refreshAheadSeconds);

        boolean wallOk = clock.instant().isBefore(usableUntilWall);
        boolean nanoOk = nanoTicker.getAsLong() < usableUntilNanos;

        return wallOk && nanoOk;
    }

    /**
     * 检查当前 Token 是否已经绝对过期（已超真实 expiresAt 或单调租约已尽）。
     *
     * @param clock Wall Clock 时钟，非空
     * @param nanoTicker 单调纳秒时钟源，非空
     * @return 若已绝对过期返回 true，否则返回 false
     */
    public boolean isExpired(Clock clock, LongSupplier nanoTicker) {
        Objects.requireNonNull(clock, "clock不能为null");
        Objects.requireNonNull(nanoTicker, "nanoTicker不能为null");

        boolean wallExpired = !clock.instant().isBefore(expiresAt);
        boolean nanoExpired = nanoTicker.getAsLong() >= leaseDeadlineNanos;

        return wallExpired || nanoExpired;
    }

    public String accessToken() {
        return accessToken;
    }

    public long generation() {
        return generation;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public long leaseDeadlineNanos() {
        return leaseDeadlineNanos;
    }

    public long refreshAheadSeconds() {
        return refreshAheadSeconds;
    }

    public Optional<HuashengExchangeEvidence> tokenEvidence() {
        return tokenEvidence;
    }

    @Override
    public String toString() {
        return "HuashengTokenEntry[redacted]";
    }
}
