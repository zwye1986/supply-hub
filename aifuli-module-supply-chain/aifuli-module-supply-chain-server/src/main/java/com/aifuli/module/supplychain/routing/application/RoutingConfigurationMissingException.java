package com.aifuli.module.supplychain.routing.application;

/**
 * 正常路由配置尚缺的封闭业务条件；只有确切读点允许抛出，坏引用、重复配置和 SQL 故障不得转换。
 * 保持 IllegalStateException 兼容旧 RPC 路由错误边界；新 Reader 只捕获本类型。
 * 设计见 {@code docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#5-当前供给reader与错误分类}。
 */
public final class RoutingConfigurationMissingException extends IllegalStateException {
    public enum Reason {
        NO_ACTIVE_POLICY, NO_EFFECTIVE_POLICY_VERSION, NO_MATCHED_RULE, NO_CONFIGURED_SUPPLY
    }

    private final String reason;

    public RoutingConfigurationMissingException(Reason reason, String message) {
        super(message);
        this.reason = reason.name();
    }

    public String reason() {
        return reason;
    }
}
