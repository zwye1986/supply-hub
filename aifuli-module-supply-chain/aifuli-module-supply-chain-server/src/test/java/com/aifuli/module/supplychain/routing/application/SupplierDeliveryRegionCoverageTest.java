package com.aifuli.module.supplychain.routing.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.aifuli.module.supplychain.routing.domain.RoutingInput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配送范围的精确与行政区划祖先覆盖测试。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#5-配送区域}。
 */
class SupplierDeliveryRegionCoverageTest {

    @Test
    void skipsRegionForOrdersWithoutDeliveryAddress() {
        assertThat(SupplierDeliveryRegionCoverage.covers(null, List.of(), null)).isTrue();
    }

    @Test
    void nationwideOrConfiguredAncestorCoversDestination() {
        assertThat(SupplierDeliveryRegionCoverage.covers("NATIONWIDE", List.of(), "110101")).isTrue();
        assertThat(SupplierDeliveryRegionCoverage.covers("DESIGNATED", List.of("110000"), "110101"))
                .isTrue();
        assertThat(SupplierDeliveryRegionCoverage.covers("DESIGNATED", List.of("110101"), "110101"))
                .isTrue();
    }

    @Test
    void unknownOrOtherAreaDoesNotMatchByPrefix() {
        assertThat(SupplierDeliveryRegionCoverage.covers("DESIGNATED", List.of("110101"), "110102"))
                .isFalse();
        assertThat(SupplierDeliveryRegionCoverage.covers("DESIGNATED", List.of("110000"), "999999"))
                .isFalse();
        assertThat(SupplierDeliveryRegionCoverage.covers(null, List.of("110101"), "110101"))
                .isFalse();
    }

    @Test
    void inputRequiresSixDigitAdcodeWhenPresent() {
        assertThatThrownBy(() -> new RoutingInput(1L, null, 2L, "DIRECT_RECHARGE", 1,
                LocalDateTime.of(2026, 9, 24, 10, 0), Map.of(), "11010"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("六位数字");
    }
}
