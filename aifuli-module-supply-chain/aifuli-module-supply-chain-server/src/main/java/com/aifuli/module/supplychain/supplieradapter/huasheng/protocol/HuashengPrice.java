package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;
/** 不可变价格事实；原文不用于日志。见 docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §5.2、§14.2。 */
public final class HuashengPrice {
    private final String rawYuan;
    private final long minorUnits;
    HuashengPrice(String rawYuan, long minorUnits) { this.rawYuan = java.util.Objects.requireNonNull(rawYuan,"缺少金额原文"); this.minorUnits = minorUnits; }
    public String rawYuan() { return rawYuan; }
    public long minorUnits() { return minorUnits; }
    @Override public String toString() { return "HuashengPrice[redacted]"; }
}
