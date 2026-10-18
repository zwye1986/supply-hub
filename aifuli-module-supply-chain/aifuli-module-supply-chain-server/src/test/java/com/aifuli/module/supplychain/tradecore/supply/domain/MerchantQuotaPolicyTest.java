package com.aifuli.module.supplychain.tradecore.supply.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

/** 滚动窗口取值由Mapper投影，策略只计算资源资格和最早再竞争时点。 */
class MerchantQuotaPolicyTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 18, 23, 0);
    @Test void availableResourcesAdmit() {
        assertThat(MerchantQuotaPolicy.retryAt(now, 2, 2, 1, 1, now.minusNanos(100_000_000), now.plusSeconds(10))).isNull();
    }
    @Test void rateOnlyWaitsUntilOldestAdmissionLeavesRollingSecond() {
        assertThat(MerchantQuotaPolicy.retryAt(now, 1, 2, 1, 0, now.minusNanos(100_000_000), null))
                .isEqualTo(now.plusNanos(900_000_000));
    }
    @Test void concurrencyOnlyWaitsUntilFirstLeaseExpires() {
        assertThat(MerchantQuotaPolicy.retryAt(now, 10, 1, 0, 1, null, now.plusSeconds(10))).isEqualTo(now.plusSeconds(10));
    }
    @Test void bothLimitsUseLaterSafeBoundary() {
        assertThat(MerchantQuotaPolicy.retryAt(now, 1, 1, 1, 1, now.minusNanos(100_000_000), now.plusSeconds(10)))
                .isEqualTo(now.plusSeconds(10));
    }
    @Test void minimumOneMillisecondAvoidsBusyLoop() {
        assertThat(MerchantQuotaPolicy.retryAt(now, 1, 1, 1, 0, now.minusSeconds(1), null)).isEqualTo(now.plusNanos(1_000_000));
    }
    @Test void invalidLimitsOrIncompleteProjectionFailClosed() {
        assertThatThrownBy(() -> MerchantQuotaPolicy.retryAt(now, 0, 1, 0, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerchantQuotaPolicy.retryAt(now, 1, -1, 0, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerchantQuotaPolicy.retryAt(now, 1, 1, 1, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
