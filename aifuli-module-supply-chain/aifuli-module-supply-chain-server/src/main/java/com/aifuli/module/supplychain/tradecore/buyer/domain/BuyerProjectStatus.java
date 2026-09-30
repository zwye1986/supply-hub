package com.aifuli.module.supplychain.tradecore.buyer.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 采购项目生命周期。
 *
 * <p>项目是业务归属与配置边界；已结束 {@code CLOSED=30} 可恢复；归档仍不可逆。
 * 设计见 {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#42-buyerproject}、
 * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum BuyerProjectStatus {
    DRAFT((short) 0),
    ACTIVE((short) 10),
    SUSPENDED((short) 20),
    CLOSED((short) 30),
    ARCHIVED((short) 90);

    private final short code;

    public static BuyerProjectStatus fromCode(short code) {
        for (BuyerProjectStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown buyer project status: " + code);
    }

    /** 判断是否允许执行指定的单步状态迁移。 */
    public boolean canTransitionTo(BuyerProjectStatus target) {
        if (target == null || this == target || this == ARCHIVED) {
            return false;
        }
        return switch (this) {
            case DRAFT -> target == ACTIVE || target == ARCHIVED;
            case ACTIVE -> target == SUSPENDED || target == CLOSED;
            case SUSPENDED -> target == ACTIVE || target == CLOSED || target == ARCHIVED;
            case CLOSED -> target == ACTIVE || target == SUSPENDED;
            case ARCHIVED -> false;
        };
    }
}
