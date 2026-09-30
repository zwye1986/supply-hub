package com.aifuli.module.supplychain.tradecore.catalog.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SKU 每人限购自然周期边界测试。 */
class PurchaseLimitPeriodTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2027, 1, 1, 12, 30);

    @Test
    void calculatesAllConfiguredNaturalWindows() {
        assertThat(PurchaseLimitPeriod.LIFETIME.windowAt(OCCURRED_AT))
                .isEqualTo(new PurchaseLimitPeriod.PeriodWindow(null, null));
        assertThat(PurchaseLimitPeriod.YEAR.windowAt(OCCURRED_AT))
                .isEqualTo(window("2027-01-01T00:00:00", "2028-01-01T00:00:00"));
        assertThat(PurchaseLimitPeriod.MONTH.windowAt(OCCURRED_AT))
                .isEqualTo(window("2027-01-01T00:00:00", "2027-02-01T00:00:00"));
        assertThat(PurchaseLimitPeriod.WEEK.windowAt(OCCURRED_AT))
                .isEqualTo(window("2026-12-28T00:00:00", "2027-01-04T00:00:00"));
        assertThat(PurchaseLimitPeriod.DAY.windowAt(OCCURRED_AT))
                .isEqualTo(window("2027-01-01T00:00:00", "2027-01-02T00:00:00"));
    }

    @Test
    void fromCodeAcceptsConfiguredPeriodsAndRejectsBlankOrUnknownValues() {
        assertThat(PurchaseLimitPeriod.fromCode("month")).isEqualTo(PurchaseLimitPeriod.MONTH);
        assertThatThrownBy(() -> PurchaseLimitPeriod.fromCode(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("限购周期不能为空");
        assertThatThrownBy(() -> PurchaseLimitPeriod.fromCode("QUARTER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知限购周期");
    }

    private static PurchaseLimitPeriod.PeriodWindow window(String start, String end) {
        return new PurchaseLimitPeriod.PeriodWindow(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }
}
