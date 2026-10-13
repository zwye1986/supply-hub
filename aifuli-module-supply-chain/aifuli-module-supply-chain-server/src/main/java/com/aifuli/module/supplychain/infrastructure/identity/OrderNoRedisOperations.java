package com.aifuli.module.supplychain.infrastructure.identity;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis原子消费边界；异常意味着执行结果不确定，调用方须永久弃段，禁止透明重试。 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#43-redis原子自增与回退保护}。
 */
public interface OrderNoRedisOperations {
  Instant time(Duration timeout);

  Result consume(String key, OrderNoSegment segment, int floor, int count, Duration timeout);

  enum Status {
    SUCCESS,
    DATE_CHANGED,
    EXHAUSTED,
    INVALID
  }

  record Result(Status status, Instant instant, int endValue) {}
}
