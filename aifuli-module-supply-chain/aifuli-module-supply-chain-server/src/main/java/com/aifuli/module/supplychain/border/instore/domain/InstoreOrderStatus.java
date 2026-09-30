package com.aifuli.module.supplychain.border.instore.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 到店购渠道订单头聚合状态，对应 {@code sc_instore_channel_order.order_status}。
 *
 * <p>取值与 DDL CHECK 约束一致：0已创建，10处理中，20已完成，30已取消，40失败，90人工处理。
 * 到店即完成，支付成功即已完成；无 O2O 的 225 推送与渠道通知收敛。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#43-InstoreOrderSummaryPolicy}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InstoreOrderStatus {
    /** 已创建：建单并冻结资金后。 */
    CREATED((short) 0, "已创建"),
    /** 处理中：等待用户支付或支付处理中。 */
    PROCESSING((short) 10, "处理中"),
    /** 已完成：任一支付流水支付成功且资金提交，到店履约完成。 */
    COMPLETED((short) 20, "已完成"),
    /** 已取消：订单关闭且无已支付流水。 */
    CANCELLED((short) 30, "已取消"),
    /** 失败。 */
    FAILED((short) 40, "失败"),
    /** 人工处理：取消已支付流水退款未收敛等无法自动收敛场景。 */
    MANUAL((short) 90, "人工处理");

    private final short code;
    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;

    public static InstoreOrderStatus fromCode(short code) {
        for (InstoreOrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown instore order status: " + code);
    }
}
