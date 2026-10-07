package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import java.util.Optional;

/**
 * 华昇订单状态枚举。
 *
 * <p>职责：定义华昇 OpenAPI 返回的订单状态枚举（WAIT, SUCCESS, FAILED, CANCEL）。
 *
 * <p>不变量：仅包含 4 种官方合法状态；严格区分大小写，不 trim；未知值返回 {@link Optional#empty()}，
 * 由解析器统一归为 STATE_CONFLICT。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.2.5。
 */
public enum HuashengOrderStatus {
    WAIT,
    SUCCESS,
    FAILED,
    CANCEL;

    /**
     * 将供应商线协议字符串解析为订单状态枚举。
     *
     * <p>职责：严格匹配华昇 OpenAPI 订单 status 字段的传输文本（WAIT, SUCCESS, FAILED, CANCEL）。
     *
     * <p>不变量：区分大小写，不执行 trim；合法值仅限 4 种官方状态；null 或任何未声明文本返回 {@link Optional#empty()}，
     * 由解析器在更高层判定为 {@code STATE_CONFLICT}，绝不静默宽容或兜底为默认状态。
     *
     * <p>关键决策：严格遵守快速失败与确定性状态机规则，禁止将未知状态猜测为处理中或失败。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#53-订单事实、§15.2.5。
     *
     * @param value 供应商返回的原始状态字符串，可为 null
     * @return 成功匹配时返回包含 {@link HuashengOrderStatus} 的 Optional，否则返回 {@link Optional#empty()}
     */
    public static Optional<HuashengOrderStatus> fromWireValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (HuashengOrderStatus status : values()) {
            if (status.name().equals(value)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
