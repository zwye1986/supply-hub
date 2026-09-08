package com.aifuli.module.supplychain.tradecore.profile.domain;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;

/**
 * 把 REST 字符串解析为封闭枚举；未知值统一 ENUM_NOT_OPEN。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。
 */
public final class ClosedEnums {
    private ClosedEnums() {
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim());
        } catch (IllegalArgumentException ex) {
            throw exception(ENUM_NOT_OPEN);
        }
    }

    public static <E extends Enum<E>> E parseRequired(Class<E> type, String name) {
        E value = parse(type, name);
        if (value == null) {
            throw exception(ENUM_NOT_OPEN);
        }
        return value;
    }
}
