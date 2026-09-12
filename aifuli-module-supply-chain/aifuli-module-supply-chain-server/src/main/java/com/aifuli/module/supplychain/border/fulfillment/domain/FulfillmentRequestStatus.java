package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 标准商品履约请求状态，对应 {@code sc_fulfillment_request.status} DDL COMMENT 与 CHECK。
 *
 * <p>该枚举用于只读查询和后续状态推进共享码值，避免管理端把短整型业务码散落在映射逻辑中。
 * 设计见 {@code docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md#32-订单详情}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum FulfillmentRequestStatus {
    PENDING_ROUTING((short) 0, "待路由"),
    ROUTING((short) 10, "路由中"),
    FULFILLING((short) 20, "履约中"),
    SUCCEEDED((short) 40, "成功"),
    FAILED((short) 50, "失败"),
    MANUAL((short) 90, "人工处理");

    private final short code;
    private final String chineseName;

    public static FulfillmentRequestStatus fromCode(short code) {
        for (FulfillmentRequestStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown fulfillment request status: " + code);
    }
}
