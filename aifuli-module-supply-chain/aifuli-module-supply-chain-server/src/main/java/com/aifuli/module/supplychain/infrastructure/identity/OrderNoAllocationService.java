package com.aifuli.module.supplychain.infrastructure.identity;

import com.aifuli.module.supplychain.infrastructure.configuration.BudgetContext;
import com.aifuli.module.supplychain.infrastructure.configuration.BudgetTimeoutException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

/**
 * 单个容器的独占号段协调器。互斥范围覆盖脚本、响应验证与floor推进；不确定结果先弃段再解锁。 实例重启生成新owner并申请新区间，已返回或已消费身份永不回收；没有数据库逐号或ULID降级。
 * 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#4-号段redis算法与故障边界}。
 */
public class OrderNoAllocationService {
    private final OrderNoSegmentStore store;
    private final OrderNoRedisOperations redis;
    private final OrderNoFormatter formatter;
    private final DataSource dataSource;
    private final int segmentSize;
    private final String env, scope;
    private final Duration lockWait, commandTimeout, reservationTimeout;
    private final String ownerId = UUID.randomUUID().toString().replace("-", "");
    private final ReentrantLock lock = new ReentrantLock();
    private OrderNoSegment current;
    private int floor;

    public OrderNoAllocationService(
            OrderNoSegmentStore store,
            OrderNoRedisOperations redis,
            OrderNoProperties properties,
            OrderNoFormatter formatter,
            DataSource dataSource) {
        properties.validate();
        this.store = Objects.requireNonNull(store);
        this.redis = Objects.requireNonNull(redis);
        this.formatter = Objects.requireNonNull(formatter);
        this.dataSource = Objects.requireNonNull(dataSource);
        this.segmentSize = properties.getSegmentSize();
        this.env = properties.getEnv();
        this.scope = properties.getScope();
        this.lockWait = properties.getLockWait();
        this.commandTimeout = properties.getCommandTimeout();
        this.reservationTimeout = properties.getReservationTimeout();
    }

    /** 只为新的创建准备身份；已有订单重试、回放与恢复不得调用本方法。 */
    public PreparedOrderNumbers prepare(OrderNoPurpose purpose) {
        OrderNoTransactionGuard.check(dataSource);
        Objects.requireNonNull(purpose);
        Duration wait = remaining(lockWait);
        boolean acquired;
        try {
            acquired = lock.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("订单编号互斥等待被中断", interrupted);
        }
        if (!acquired) throw new BudgetTimeoutException("订单编号互斥等待超时");
        try {
            remaining(commandTimeout);
            int count = purpose.count();
            LocalDate nextDate = null;
            for (int correction = 0; correction <= 1; correction++) {
                if (current == null || (long) floor + count > current.endValue()) {
                    // 放弃不足当前批次的余号后再读取Redis时间；TIME失败也不恢复旧段。
                    current = null;
                    if (nextDate == null) nextDate = date(redis.time(remaining(commandTimeout)));
                    OrderNoSegment reserved =
                            store.reserve(
                                    nextDate,
                                    ownerId,
                                    segmentSize,
                                    count,
                                    remaining(reservationTimeout));
                    if (reserved == null
                            || !reserved.date().equals(nextDate)
                            || !reserved.ownerId().equals(ownerId)
                            || reserved.endValue() - reserved.startValue() + 1 < count)
                        throw new IllegalStateException("订单编号预留响应违反号段所有权或容量约束");
                    remaining(commandTimeout);
                    current = reserved;
                    floor = reserved.startValue() - 1;
                }
                OrderNoSegment requested = current;
                OrderNoRedisOperations.Result result;
                try {
                    result =
                            redis.consume(
                                    key(requested),
                                    requested,
                                    floor,
                                    count,
                                    remaining(commandTimeout));
                    validate(result, requested, floor, count);
                    if (result.status() == OrderNoRedisOperations.Status.SUCCESS) {
                        // 先兑现不可重发承诺，格式化失败或预算取消均不能回减。
                        floor = result.endValue();
                    } else {
                        current = null;
                    }
                } catch (RuntimeException uncertain) {
                    current = null;
                    throw uncertain;
                }
                remaining(commandTimeout);
                if (result.status() == OrderNoRedisOperations.Status.SUCCESS) {
                    int first = result.endValue() - count + 1;
                    String orderNo = formatter.format(result.instant(), first);
                    String billno =
                            purpose == OrderNoPurpose.ORDER_AND_BILL
                                    ? formatter.format(result.instant(), result.endValue())
                                    : null;
                    remaining(commandTimeout);
                    return new PreparedOrderNumbers(orderNo, billno);
                }
                nextDate = date(result.instant());
            }
            throw new IllegalStateException("订单编号跨日或号段修正超过单次允许次数");
        } finally {
            lock.unlock();
        }
    }

    private String key(OrderNoSegment segment) {
        return "supply-chain:order-no:{"
                + env
                + ":"
                + scope
                + ":"
                + segment.date()
                + ":"
                + segment.ownerId()
                + ":"
                + segment.startValue()
                + "}:cursor";
    }

    private static LocalDate date(Instant instant) {
        return Objects.requireNonNull(instant, "Redis时间不可为空")
                .atZone(OrderNoFormatter.ZONE)
                .toLocalDate();
    }

    private static void validate(
            OrderNoRedisOperations.Result result, OrderNoSegment segment, int floor, int count) {
        if (result == null || result.status() == null || result.instant() == null)
            throw new IllegalStateException("订单编号Redis响应不完整");
        boolean sameDate = segment.date().equals(date(result.instant()));
        switch (result.status()) {
            case SUCCESS -> {
                if (!sameDate
                        || result.endValue() < floor + count
                        || result.endValue() > segment.endValue())
                    throw new IllegalStateException("订单编号Redis消费响应违反时间或流水约束");
            }
            case DATE_CHANGED -> {
                if (sameDate || result.endValue() != 0)
                    throw new IllegalStateException("订单编号Redis跨日响应非法");
            }
            case EXHAUSTED -> {
                if (!sameDate
                        || result.endValue() < floor
                        || result.endValue() > segment.endValue()
                        || (long) result.endValue() + count <= segment.endValue())
                    throw new IllegalStateException("订单编号Redis耗尽响应非法");
            }
            case INVALID -> throw new IllegalStateException("订单编号Redis游标不变量故障");
        }
    }

    static Duration remaining(Duration configured) {
        if (Thread.currentThread().isInterrupted()) throw new BudgetTimeoutException("订单编号调用已中断");
        BudgetContext budget = BudgetContext.current();
        if (budget == null) return configured;
        long nanos = budget.remainingNanos();
        if (budget.isCancelled() || budget.isCleanupFailed() || nanos <= 0)
            throw new BudgetTimeoutException("订单编号请求已取消或预算耗尽");
        return Duration.ofNanos(Math.min(configured.toNanos(), nanos));
    }
}
