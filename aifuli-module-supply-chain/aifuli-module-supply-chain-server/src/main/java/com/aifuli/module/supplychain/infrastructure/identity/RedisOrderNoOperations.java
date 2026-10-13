package com.aifuli.module.supplychain.infrastructure.identity;

import com.aifuli.module.supplychain.infrastructure.configuration.BudgetTimeoutException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.OptionalOptions;
import org.redisson.client.RedisNoScriptException;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;

/**
 * 复用已有Redisson客户端，仅对本脚本对象设置StringCodec、命令超时和零透明重试。 不创建连接池或执行器。异步结果由请求线程有界等待，迟到结果不回调分配器状态；
 * 仅明确未执行的NOSCRIPT允许一次原脚本执行，其余异常保留因果并交由分配器弃段。 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#43-redis原子自增与回退保护}及§10.1。
 */
public class RedisOrderNoOperations implements OrderNoRedisOperations {
  private static final long CANCEL_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
  private static final String TIME_SCRIPT = "return redis.call('TIME')";
  private final RedissonClient client;
  private final String script;
  private final String sha;

  public RedisOrderNoOperations(RedissonClient client) {
    this.client = Objects.requireNonNull(client);
    try {
      script =
          new ClassPathResource("identity/order-no-consume.lua")
              .getContentAsString(StandardCharsets.UTF_8);
      sha =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-1")
                      .digest(script.getBytes(StandardCharsets.UTF_8)));
    } catch (IOException | NoSuchAlgorithmException invalid) {
      throw new IllegalStateException("订单编号Redis消费脚本无法初始化", invalid);
    }
  }

  @Override
  public Instant time(Duration timeout) {
    Duration allowed = checked(timeout);
    long deadline = System.nanoTime() + allowed.toNanos();
    List<?> values =
        await(
            script(allowed)
                .evalAsync(RScript.Mode.READ_ONLY, TIME_SCRIPT, RScript.ReturnType.LIST, List.of()),
            deadline);
    if (values == null || values.size() != 2) throw new IllegalStateException("订单编号Redis TIME响应非法");
    return instant(values.get(0), values.get(1));
  }

  @Override
  public Result consume(
      String key, OrderNoSegment segment, int floor, int count, Duration timeout) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(segment);
    Duration allowed = checked(timeout);
    long deadline = System.nanoTime() + allowed.toNanos();
    long start = segment.date().atStartOfDay(OrderNoFormatter.ZONE).toEpochSecond();
    long end = segment.date().plusDays(1).atStartOfDay(OrderNoFormatter.ZONE).toEpochSecond();
    Object[] args = {
      Long.toString(start),
      Long.toString(end),
      Integer.toString(segment.startValue()),
      Integer.toString(segment.endValue()),
      Integer.toString(floor),
      Integer.toString(count)
    };
    List<Object> keys = List.of(key);
    List<?> values;
    try {
      values =
          await(
              script(allowed)
                  .evalShaAsync(RScript.Mode.READ_WRITE, sha, RScript.ReturnType.LIST, keys, args),
              deadline);
    } catch (RedisNoScriptException unexecuted) {
      // NOSCRIPT证明此次尚未执行，仍在同一总期限内有界重载并执行一次。
      Duration left = checked(Duration.ofNanos(deadline - System.nanoTime()));
      values =
          await(
              script(left)
                  .evalAsync(RScript.Mode.READ_WRITE, script, RScript.ReturnType.LIST, keys, args),
              deadline);
    }
    if (values == null || values.size() != 4) throw new IllegalStateException("订单编号Redis消费响应格式非法");
    try {
      Status status = Status.valueOf(Objects.toString(values.get(0)));
      int sequence = Math.toIntExact(decimal(values.get(3)));
      return new Result(status, instant(values.get(1), values.get(2)), sequence);
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      throw new IllegalStateException("订单编号Redis消费响应字段非法", invalid);
    }
  }

  private RScript script(Duration timeout) {
    return client.getScript(
        OptionalOptions.defaults().codec(StringCodec.INSTANCE).timeout(timeout).retryAttempts(0));
  }

  private static Duration checked(Duration timeout) {
    Duration remaining = OrderNoAllocationService.remaining(Objects.requireNonNull(timeout));
    if (remaining.compareTo(Duration.ofMillis(1)) < 0)
      throw new BudgetTimeoutException("订单编号Redis命令预算不足1毫秒");
    return remaining;
  }

  private static Instant instant(Object seconds, Object microseconds) {
    long sec = decimal(seconds), micros = decimal(microseconds);
    if (sec < 0 || micros < 0 || micros > 999_999)
      throw new IllegalArgumentException("Redis时间响应越界");
    return Instant.ofEpochSecond(sec, micros * 1000);
  }

  private static long decimal(Object value) {
    String text = Objects.toString(value, "");
    if (!text.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Redis响应须为非负十进制整数");
    return Long.parseLong(text);
  }

  private static <T> T await(RFuture<T> future, long deadline) {
    try {
      while (true) {
        long nanos = deadline - System.nanoTime();
        OrderNoAllocationService.remaining(Duration.ofNanos(Math.max(nanos, 1)));
        if (nanos <= 0) throw new BudgetTimeoutException("订单编号Redis等待超时，执行结果不确定");
        try {
          T value = future.get(Math.min(nanos, CANCEL_POLL_NANOS), TimeUnit.NANOSECONDS);
          OrderNoAllocationService.remaining(
              Duration.ofNanos(Math.max(deadline - System.nanoTime(), 1)));
          if (System.nanoTime() >= deadline)
            throw new BudgetTimeoutException("订单编号Redis响应超出期限，执行结果不确定");
          return value;
        } catch (TimeoutException waiting) {
          // 短轮询仅检查取消/剩余预算，不发送第二次Redis命令。
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("订单编号Redis等待被中断，执行结果不确定", interrupted);
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("订单编号Redis执行失败，执行结果不确定", failed.getCause());
    } finally {
      if (!future.isDone()) future.cancel(false);
    }
  }
}
