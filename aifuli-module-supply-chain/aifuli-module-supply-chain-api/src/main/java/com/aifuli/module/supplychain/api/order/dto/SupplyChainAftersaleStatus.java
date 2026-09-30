package com.aifuli.module.supplychain.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Schema(description = "供应链总订单售后状态")
@Getter
@RequiredArgsConstructor
public enum SupplyChainAftersaleStatus {
    NONE((short) 0),
    PROCESSING((short) 10),
    PARTIALLY_REFUNDED((short) 20),
    FULLY_REFUNDED((short) 30),
    MANUAL((short) 90);

    private final short code;

    /**
     * 把总订单 DDL 中的稳定短整型码转换为对外枚举名；未知码说明数据违反 CHECK 约束。
     * 设计见 {@code docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md#31-分页列表}。
     */
    public static SupplyChainAftersaleStatus fromCode(short code) {
        for (SupplyChainAftersaleStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown supply-chain aftersale status: " + code);
    }
}
