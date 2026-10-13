package com.aifuli.module.supplychain.infrastructure.identity;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 供应链独立库发号域与有界等待配置；env/scope必须显式提供，避免错误环境默默共用键域。 设计见 {@code
 * docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#43-redis原子自增与回退保护}及§7发布前置。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "aifuli.supply-chain.order-no")
public class OrderNoProperties {
  private int segmentSize = 1000;
  private String env;
  private String scope;
  private Duration lockWait = Duration.ofMillis(200);
  private Duration commandTimeout = Duration.ofMillis(1000);
  private Duration reservationTimeout = Duration.ofSeconds(3);

  public void validate() {
    if (segmentSize < 2 || segmentSize > 10_000)
      throw new IllegalArgumentException("订单号段大小须为2至10000");
    domain(env, "env");
    domain(scope, "scope");
    bounded(lockWait, "lockWait");
    bounded(commandTimeout, "commandTimeout");
    bounded(reservationTimeout, "reservationTimeout");
  }

  private static void domain(String value, String name) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("订单编号" + name + "须显式配置为1至64位字母数字下划线或连字符");
  }

  private static void bounded(Duration value, String name) {
    if (value == null
        || value.compareTo(Duration.ofMillis(1)) < 0
        || value.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("订单编号" + name + "须在1毫秒至30秒范围内");
  }
}
