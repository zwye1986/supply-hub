package com.aifuli.module.supplychain.tradecore.supply.domain;

import java.time.LocalDateTime;

/** 商户额度纯策略，设计：docs/superpowers/specs/2026-10-18-single-step-merchant-quota-design.md#61-速率和并发定义。 */
public final class MerchantQuotaPolicy {
    private MerchantQuotaPolicy() { }
    public static LocalDateTime retryAt(LocalDateTime now, int rate, int concurrency, long recent, long active,
                                       LocalDateTime firstAdmission, LocalDateTime firstExpiry) {
        if (now == null || rate <= 0 || concurrency <= 0 || recent < 0 || active < 0
                || recent >= rate && firstAdmission == null || active >= concurrency && firstExpiry == null) {
            throw new IllegalArgumentException("商户额度配置或资源投影无效");
        }
        LocalDateTime retry = null;
        if (recent >= rate) retry = firstAdmission.plusSeconds(1);
        if (active >= concurrency && (retry == null || firstExpiry.isAfter(retry))) retry = firstExpiry;
        if (retry == null) return null;
        var minimum = now.plusNanos(1_000_000);
        return retry.isAfter(minimum) ? retry : minimum;
    }
}
