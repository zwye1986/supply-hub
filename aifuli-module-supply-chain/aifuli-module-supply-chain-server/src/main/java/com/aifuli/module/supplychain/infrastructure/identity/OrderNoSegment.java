package com.aifuli.module.supplychain.infrastructure.identity;

import java.time.LocalDate;
import java.util.Objects;

/**
 * MySQL提交后确认的独占号段；不转移、不回收，Redis键同时绑定日期、owner和起点。 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#41-mysql永久预留与所有权}。
 */
public record OrderNoSegment(LocalDate date, int startValue, int endValue, String ownerId) {
  public OrderNoSegment {
    Objects.requireNonNull(date);
    if (startValue < 1 || startValue > endValue || endValue > OrderNoFormatter.MAX_SEQUENCE)
      throw new IllegalArgumentException("订单编号号段范围非法");
    if (ownerId == null || !ownerId.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("订单编号owner须为本次分配器启动标识");
  }
}
