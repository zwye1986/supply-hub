package com.aifuli.module.supplychain.infrastructure.identity;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OrderNoPropertiesTest {
  static OrderNoProperties valid() {
    var p = new OrderNoProperties();
    p.setEnv("test");
    p.setScope("supply-chain-db");
    return p;
  }

  @Test
  void explicitDomainAndBoundedSegmentRequired() {
    var p = valid();
    assertThat(p.getSegmentSize()).isEqualTo(1000);
    p.validate();
    p.setSegmentSize(1);
    assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
    p.setSegmentSize(10001);
    assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
    p.setSegmentSize(2);
    p.setScope("bad:{scope}");
    assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderNoProperties().validate())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesUnboundedOrZeroWaits() {
    var p = valid();
    p.setCommandTimeout(Duration.ZERO);
    assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
    p.setCommandTimeout(Duration.ofSeconds(31));
    assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
    p.setCommandTimeout(Duration.ofMillis(100));
    p.setLockWait(Duration.ofSeconds(31));
    assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
  }
}
