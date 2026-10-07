package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import java.util.Optional;

/**
 * 华昇订单退款状态枚举。
 *
 * <p>职责：定义华昇 OpenAPI 返回的退款状态枚举（NONE, WAIT, SUCCESS）。
 *
 * <p>不变量：仅包含 3 种官方合法退款状态；严格区分大小写，不 trim；未知值返回 {@link Optional#empty()}，
 * 由解析器统一归为 STATE_CONFLICT。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.2.5。
 */
public enum HuashengRefundStatus {
    NONE,
    WAIT,
    SUCCESS;

    /**
     * 将供应商线协议字符串解析为订单退款状态枚举。
     *
     * <p>职责：严格匹配华昇 OpenAPI 订单 refundStatus 字段的传输文本（NONE, WAIT, SUCCESS）。
     *
     * <p>不变量：区分大小写，不执行 trim；合法值仅限 3 种官方退款状态；null 或任何未声明文本返回 {@link Optional#empty()}，
     * 由解析器判定为 {@code STATE_CONFLICT}，绝不静默默认或猜测。
     *
     * <p>关键决策：退款状态与主订单状态正交，由解析器矩阵校验二者组合一致性（如 SUCCESS 与退款中/成功互斥）。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.2.5。
     *
     * @param value 供应商返回的原始退款状态字符串，可为 null
     * @return 成功匹配时返回包含 {@link HuashengRefundStatus} 的 Optional，否则返回 {@link Optional#empty()}
     */
    public static Optional<HuashengRefundStatus> fromWireValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (HuashengRefundStatus status : values()) {
            if (status.name().equals(value)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
