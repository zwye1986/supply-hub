package com.aifuli.module.supplychain.border.fulfillment.domain;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 单次出站调用拥有的按键材料；接管数组而不制造不可擦除的String，调用方不得再读写交入数组。
 * 消费回调只借用临时副本，不得留存或将其拼入日志/异常；副本在回调退出时擦除。
 * 设计见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#32-ticket扩展。
 */
@JsonSerialize(using = FulfillmentInternalOnlySerializer.class)
public final class ResolvedCredentialMaterial implements AutoCloseable {
    private final Map<String, char[]> values;
    private boolean closed;

    private ResolvedCredentialMaterial(Map<String, char[]> values) {
        this.values = Map.copyOf(values);
    }

    /** 校验完整的代码声明键；即使接管失败，也擦除所有已交入的材料。 */
    public static ResolvedCredentialMaterial takeOwnership(Set<String> requiredKeys, Map<String, char[]> values) {
        try {
            if (requiredKeys == null || requiredKeys.isEmpty() || values == null
                    || !requiredKeys.equals(values.keySet())
                    || requiredKeys.stream().anyMatch(key -> key == null || key.isBlank())
                    || values.values().stream().anyMatch(chars -> chars == null || chars.length == 0)) {
                throw new IllegalArgumentException("出站凭据材料与声明键不一致或材料为空");
            }
            return new ResolvedCredentialMaterial(values);
        } catch (RuntimeException | Error failure) {
            if (values != null) values.values().forEach(chars -> { if (chars != null) Arrays.fill(chars, '\0'); });
            throw failure;
        }
    }

    /** 不暴露所有权内数组；成功和异常退出都擦除临时副本，关闭后不可消费。 */
    public synchronized <T> T use(String key, Function<char[], T> consumer) {
        if (closed) throw new IllegalStateException("出站凭据材料已关闭");
        char[] value = values.get(key);
        if (value == null) throw new IllegalArgumentException("未声明的出站凭据键");
        char[] copy = value.clone();
        try {
            return consumer.apply(copy);
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    /** Ticket/调用者在单次签名及HTTP结束后关闭；重复关闭安全。 */
    @Override
    public synchronized void close() {
        if (!closed) {
            values.values().forEach(chars -> Arrays.fill(chars, '\0'));
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "ResolvedCredentialMaterial[redacted]";
    }
}
