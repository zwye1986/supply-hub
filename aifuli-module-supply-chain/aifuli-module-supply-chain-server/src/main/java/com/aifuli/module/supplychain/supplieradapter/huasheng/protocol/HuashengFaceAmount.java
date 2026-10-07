package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;
/** 面值只保原文，不推导成本或新增Long分限制。见 docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §5.2、§14.2。 */
public final class HuashengFaceAmount {
    private final String rawYuan;
    HuashengFaceAmount(String rawYuan) { this.rawYuan=java.util.Objects.requireNonNull(rawYuan,"缺少面值原文"); }
    public String rawYuan() { return rawYuan; }
    @Override public String toString() { return "HuashengFaceAmount[redacted]"; }
}
