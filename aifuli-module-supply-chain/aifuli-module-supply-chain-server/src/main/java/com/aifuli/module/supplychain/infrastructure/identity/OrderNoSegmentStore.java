package com.aifuli.module.supplychain.infrastructure.identity;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 永久预留接口，只可从实际事务和绑定连接之外调用。设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#42-申请号段的短事务}。
 */
public interface OrderNoSegmentStore {
  OrderNoSegment reserve(
      LocalDate date, String ownerId, int segmentSize, int count, Duration timeout);
}
