package com.aifuli.module.supplychain.tradecore.catalog.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * SKU 限购的自然周期。
 *
 * <p>周期边界使用供应链数据库统一的 Asia/Shanghai 本地时间，ISO 自然周从周一开始。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
 */
public enum PurchaseLimitPeriod {
    LIFETIME,
    YEAR,
    MONTH,
    WEEK,
    DAY;

    public static PurchaseLimitPeriod fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("限购周期不能为空");
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知限购周期", exception);
        }
    }

    /** 计算左闭右开的自然周期窗口；终身限购不设置时间边界。 */
    public PeriodWindow windowAt(LocalDateTime occurredAt) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("限购计算时间不能为空");
        }
        if (this == LIFETIME) {
            return new PeriodWindow(null, null);
        }
        LocalDate date = occurredAt.toLocalDate();
        return switch (this) {
            case YEAR -> {
                LocalDate start = date.withDayOfYear(1);
                yield new PeriodWindow(start.atStartOfDay(), start.plusYears(1).atStartOfDay());
            }
            case MONTH -> {
                LocalDate start = date.withDayOfMonth(1);
                yield new PeriodWindow(start.atStartOfDay(), start.plusMonths(1).atStartOfDay());
            }
            case WEEK -> {
                LocalDate start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new PeriodWindow(start.atStartOfDay(), start.plusWeeks(1).atStartOfDay());
            }
            case DAY -> new PeriodWindow(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
            case LIFETIME -> throw new IllegalStateException("终身周期不应计算日期边界");
        };
    }

    public record PeriodWindow(LocalDateTime startInclusive, LocalDateTime endExclusive) {
    }
}
