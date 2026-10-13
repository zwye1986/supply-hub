package com.aifuli.module.supplychain.infrastructure.identity;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class OrderNoFormatterTest {
  @Test
  void fixedAsciiWidthUsesShanghaiRedisTimeRegardlessOfLocale() {
    Locale old = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("ar-EG"));
      assertThat(
              new OrderNoFormatter(() -> 7).format(Instant.parse("2026-10-13T07:30:27.999999Z"), 1))
          .isEqualTo("202610131530270700000001");
      assertThat(
              new OrderNoFormatter(() -> 99)
                  .format(Instant.parse("2026-10-13T16:00:00Z"), 99999999))
          .isEqualTo("202610140000009999999999");
    } finally {
      Locale.setDefault(old);
    }
  }

  @Test
  void rejectsOutOfRangeSequenceAndRandom() {
    assertThatThrownBy(() -> new OrderNoFormatter(() -> 0).format(Instant.now(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderNoFormatter(() -> 100).format(Instant.now(), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void immutablePreparedBatchRejectsInvalidDigitsAndEqualNumbers() {
    assertThatThrownBy(() -> new PreparedOrderNumbers("SC_123", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PreparedOrderNumbers("٢".repeat(24), null))
        .isInstanceOf(IllegalArgumentException.class);
    String n = "202610131530270700000001";
    assertThatThrownBy(() -> new PreparedOrderNumbers(n, n))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new PreparedOrderNumbers(n, null).orderNo()).isEqualTo(n);
  }
}
