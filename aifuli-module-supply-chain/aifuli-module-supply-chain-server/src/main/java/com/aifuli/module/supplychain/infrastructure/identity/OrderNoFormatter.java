package com.aifuli.module.supplychain.infrastructure.identity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

/**
 * 使用同次Redis消费时刻形成上海时间前缀；随机段不承担唯一性，流水固定八位。 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#21-格式}。
 */
public class OrderNoFormatter {
  public static final int MAX_SEQUENCE = 99_999_999;
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT).withZone(ZONE);
  private final IntSupplier random;

  public OrderNoFormatter() {
    this(() -> ThreadLocalRandom.current().nextInt(100));
  }

  OrderNoFormatter(IntSupplier random) {
    this.random = Objects.requireNonNull(random);
  }

  public String format(Instant instant, int sequence) {
    if (sequence < 1 || sequence > MAX_SEQUENCE) throw new IllegalArgumentException("订单编号流水越界");
    int rr = random.getAsInt();
    if (rr < 0 || rr > 99) throw new IllegalArgumentException("订单编号随机段越界");
    String value =
        TIME.format(Objects.requireNonNull(instant))
            + String.format(Locale.ROOT, "%02d%08d", rr, sequence);
    if (!value.matches("[0-9]{24}")) throw new IllegalArgumentException("订单编号时间超出四位年份格式");
    return value;
  }
}
