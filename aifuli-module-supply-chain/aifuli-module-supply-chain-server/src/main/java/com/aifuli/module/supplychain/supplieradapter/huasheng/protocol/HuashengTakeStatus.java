package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import java.util.Optional;

/**
 * 华昇订单提卡状态枚举。
 *
 * <p>职责：定义华昇 OpenAPI 返回的提卡状态枚举（N, Y）。
 *
 * <p>不变量：仅包含 N（未提卡）与 Y（已提卡）；严格区分大小写，不 trim；未知值返回 {@link Optional#empty()}，
 * 由解析器统一归为 STATE_CONFLICT。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.2.5。
 */
public enum HuashengTakeStatus {
    N,
    Y;

    /**
     * 将供应商线协议字符串解析为订单提卡状态枚举。
     *
     * <p>职责：严格匹配华昇 OpenAPI 订单 isTake 字段的传输文本（N 表示未提卡，Y 表示已提卡）。
     *
     * <p>不变量：区分大小写，不执行 trim；合法值仅限 N 与 Y；null 或任何其他文本返回 {@link Optional#empty()}，
     * 由解析器判定为 {@code STATE_CONFLICT}。
     *
     * <p>关键决策：提卡状态与主订单状态由解析器矩阵联动约束（例如 WAIT 状态下 isTake=Y 视为矛盾）。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.2.5。
     *
     * @param value 供应商返回的原始提卡状态字符串，可为 null
     * @return 成功匹配时返回包含 {@link HuashengTakeStatus} 的 Optional，否则返回 {@link Optional#empty()}
     */
    public static Optional<HuashengTakeStatus> fromWireValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (HuashengTakeStatus status : values()) {
            if (status.name().equals(value)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
