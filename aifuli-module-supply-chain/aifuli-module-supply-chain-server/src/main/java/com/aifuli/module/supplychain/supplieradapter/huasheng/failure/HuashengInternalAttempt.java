package com.aifuli.module.supplychain.supplieradapter.huasheng.failure;
/** 内部协议carrier，非业务公开结果。见 docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2。 */
@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = HuashengInternalOnlySerializer.class)
public sealed abstract class HuashengInternalAttempt<T> permits HuashengInternalAttempt.Parsed, HuashengInternalAttempt.Failed {
    private HuashengInternalAttempt() { }
    /**
     * 成功分支工厂：创建持有非空值的 {@code Parsed} 互斥分支。
     *
     * <p>职责：协议执行链内部表达“解析/执行成功”，与 {@link #failed(HuashengProtectedFailure)}
     * 构成 sealed 互斥状态。
     *
     * <p>不变量：值非空，空则以固定安全说明拒绝；Parsed 与 Failed 构造均 private，只能经本工厂创建；
     * toString 固定类别（{@code HuashengInternalAttempt[parsed]}），不包含值原文；不实现 Serializable；
     * 不得跨业务公开边界返回该 carrier，facade 是唯一对外转换边界。
     *
     * <p>关键决策：carrier 虽因跨协议链组合需要 public，仍不进入 Controller DTO、审计、Outbox 或
     * 持久表；意外 Jackson 序列化由类型级 {@link HuashengInternalOnlySerializer} 固定安全拒绝。
     *
     * <p>设计：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2。
     *
     * @param value 成功值，非空
     * @param <T> 成功值类型
     * @return 成功的内部 carrier
     */
    public static <T> HuashengInternalAttempt<T> parsed(T value) { return new Parsed<>(java.util.Objects.requireNonNull(value,"缺少内部成功值")); }
    /**
     * 失败分支工厂：创建持有非空 {@link HuashengProtectedFailure} 的 {@code Failed} 互斥分支。
     *
     * <p>职责：协议执行链内部表达“失败且保留原因果”，原始异常不得穿透 facade 边界。
     *
     * <p>不变量：failure 非空，空则以固定安全说明拒绝；分支 getter 仅内部组合消费，不能把 Failed
     * 直接返回公开业务边界；toString 固定为 {@code HuashengInternalAttempt[failed]}，不包含 failure/
     * 原 cause 表达；意外序列化由 {@link HuashengInternalOnlySerializer} 拒绝。
     *
     * <p>关键决策：失败不抛原输入异常（Error/OOME 除外），parser/transport 捕获后沿本 carrier 流转，
     * 由应用 facade 统一投影为安全诊断或公开安全异常。
     *
     * <p>设计：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2。
     *
     * @param failure 受保护失败对象，非空
     * @param <T> 若成功时的值类型（失败分支不使用）
     * @return 失败的内部 carrier
     */
    public static <T> HuashengInternalAttempt<T> failed(HuashengProtectedFailure failure) { return new Failed<>(java.util.Objects.requireNonNull(failure,"缺少内部失败值")); }
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = HuashengInternalOnlySerializer.class)
    public static final class Failed<T> extends HuashengInternalAttempt<T> {
        private final HuashengProtectedFailure failure;
        private Failed(HuashengProtectedFailure failure) { this.failure=failure; }
        public HuashengProtectedFailure failure() { return failure; }
        @Override public String toString() { return "HuashengInternalAttempt[failed]"; }
    }
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = HuashengInternalOnlySerializer.class)
    public static final class Parsed<T> extends HuashengInternalAttempt<T> {
        private final T value;
        private Parsed(T value) { this.value = value; }
        public T value() { return value; }
        @Override public String toString() { return "HuashengInternalAttempt[parsed]"; }
    }
}
