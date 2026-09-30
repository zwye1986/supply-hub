package com.aifuli.module.supplychain.tradecore.profile.domain;

import com.aifuli.framework.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception0;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;

/**
 * 把 REST 字符串解析为封闭枚举；未知值统一 ENUM_NOT_OPEN，msg 回显类型、提交值与允许值。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。
 */
public final class ClosedEnums {
    private static final Logger log = LoggerFactory.getLogger(ClosedEnums.class);

    private ClosedEnums() {
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim());
        } catch (IllegalArgumentException ex) {
            ServiceException invalid = unknown(type, name.trim());
            invalid.initCause(ex);
            throw invalid;
        }
    }

    public static <E extends Enum<E>> E parseRequired(Class<E> type, String name) {
        E value = parse(type, name);
        if (value == null) {
            throw unknown(type, name);
        }
        return value;
    }

    private static ServiceException unknown(Class<? extends Enum<?>> type, String raw) {
        String allowed = Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.joining("、"));
        String actual = raw == null ? "" : raw.trim();
        log.warn("未知枚举值 type={} value={} allowed={}", type.getSimpleName(), actual, allowed);
        if (actual.isEmpty()) {
            return exception0(ENUM_NOT_OPEN.getCode(),
                    "未知枚举值：{} 不能为空，允许值：{}", type.getSimpleName(), allowed);
        }
        return exception0(ENUM_NOT_OPEN.getCode(),
                "未知枚举值：{} 不支持 {}，允许值：{}", type.getSimpleName(), actual, allowed);
    }
}
