package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 华昇单次操作调用总预算模型。
 *
 * <p>职责：
 * 严格限制单次业务外呼的最大物理发送次数（本地上限为 2，范围限定于 {@code [1, 2]}），
 * 并持有单调时钟绝对截止点 {@code deadlineNanos}，在每次发送前执行原子检查。
 *
 * <p>不变量：
 * <ul>
 *   <li>maxBusinessAttempts 必须在 {@code [1, 2]} 闭区间内；</li>
 *   <li>deadlineNanos 必须为正整数；</li>
 *   <li>{@link #tryAcquireBusinessAttempt(long)} 线程安全原子尝试递增；超期或超额原子返回 false；</li>
 *   <li>{@link #toString()} 严格脱敏，仅输出配置最大数与已使用数；</li>
 *   <li>类级标注 {@link JsonSerialize}(using = {@link HuashengInternalOnlySerializer}.class)。</li>
 * </ul>
 *
 * <p>关键决策：
 * 禁止任意重置或绕过调用计数；超时判定使用绝对单调纳秒比较，杜绝由于时钟回拨导致预算失效。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#184-有界-61-重试编排预算与调用门面ad-a5-06--ad-a5-08、§8.3。
 */
@JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengCallBudget {

    private final int maxBusinessAttempts;
    private final long deadlineNanos;
    private final AtomicInteger usedBusinessAttempts = new AtomicInteger(0);

    /**
     * 构造调用预算。
     *
     * @param maxBusinessAttempts 最大业务发送次数，取值范围必须为 1 或 2
     * @param deadlineNanos 单调截止点纳秒值，大于 0
     * @throws IllegalArgumentException 当参数超出安全范围时抛出
     */
    public HuashengCallBudget(int maxBusinessAttempts, long deadlineNanos) {
        if (maxBusinessAttempts < 1 || maxBusinessAttempts > 2) {
            throw new IllegalArgumentException("maxBusinessAttempts必须在[1, 2]范围内: " + maxBusinessAttempts);
        }
        if (deadlineNanos <= 0L) {
            throw new IllegalArgumentException("deadlineNanos必须为正整数: " + deadlineNanos);
        }
        this.maxBusinessAttempts = maxBusinessAttempts;
        this.deadlineNanos = deadlineNanos;
    }

    /**
     * 尝试获取一次业务发送配额。
     *
     * @param currentNano 当前单调纳秒时间戳
     * @return 若未超时且有剩余额度则原子递增并返回 true，否则返回 false
     */
    public boolean tryAcquireBusinessAttempt(long currentNano) {
        if (isDeadlineExceeded(currentNano)) {
            return false;
        }
        while (true) {
            int current = usedBusinessAttempts.get();
            if (current >= maxBusinessAttempts) {
                return false;
            }
            if (usedBusinessAttempts.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 检查当前单调纳秒时间是否已达到或超过截止点。
     *
     * @param currentNano 当前单调纳秒
     * @return 若已超期返回 true，否则返回 false
     */
    public boolean isDeadlineExceeded(long currentNano) {
        return currentNano >= deadlineNanos;
    }

    public int maxBusinessAttempts() {
        return maxBusinessAttempts;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public int usedBusinessAttempts() {
        return usedBusinessAttempts.get();
    }

    public int remainingBusinessAttempts() {
        return Math.max(0, maxBusinessAttempts - usedBusinessAttempts.get());
    }

    @Override
    public String toString() {
        return "HuashengCallBudget[max=" + maxBusinessAttempts + ",used=" + usedBusinessAttempts.get() + "]";
    }
}
