package com.aifuli.module.supplychain.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Schema(description = "供应链总订单公共状态")
@Getter
@RequiredArgsConstructor
public enum SupplyChainOrderStatus {
    CREATED((short) 0),
    PROCESSING((short) 10),
    PARTIALLY_SUCCEEDED((short) 20),
    SUCCEEDED((short) 30),
    FAILED((short) 40),
    CANCELLED((short) 50),
    MANUAL((short) 90);

    private final short code;

    /**
     * 把总订单 DDL 中的稳定短整型码转换为对外枚举名；未知码说明数据违反 CHECK 约束。
     * 设计见 {@code docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md#31-分页列表}。
     */
    public static SupplyChainOrderStatus fromCode(short code) {
        for (SupplyChainOrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown supply-chain order status: " + code);
    }
}
