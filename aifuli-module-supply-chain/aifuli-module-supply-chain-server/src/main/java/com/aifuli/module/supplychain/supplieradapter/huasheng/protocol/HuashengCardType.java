package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import java.util.Optional;

/**
 * 华昇协议支持的卡券交付类型枚举。
 *
 * <p>职责：定义华昇 OpenAPI 卡片 type 字段支持的交付类型枚举（201~304 七型）。
 *
 * <p>不变量：仅包含官方规定的 7 种卡型；原始整数值严格对应，不支持任何业务魔法值。
 *
 * <p>关键决策：仅通过 {@link #fromRawValue(int)} 进行数值匹配，未匹配值返回 {@link Optional#empty()}，
 * 由解析器统一归为 CARD_PAYLOAD_INVALID。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
 */
public enum HuashengCardType {
    T201(201),
    T202(202),
    T203(203),
    T301(301),
    T302(302),
    T303(303),
    T304(304);

    private final int rawValue;

    HuashengCardType(int rawValue) {
        this.rawValue = rawValue;
    }

    /**
     * 将供应商返回的原始卡型整数转换为卡券交付类型枚举。
     *
     * <p>职责：匹配华昇 OpenAPI 支持的交付类型码（201, 202, 203, 301, 302, 303, 304 七型）。
     *
     * <p>不变量：仅接受官方规定的 7 种卡型码；任何未知整数（如 101、401 等）返回 {@link Optional#empty()}，
     * 由解析器统一拒绝为 {@code CARD_PAYLOAD_INVALID}；绝不抛出异常。
     *
     * <p>关键决策：强类型绑定卡券载荷结构，每种类型对应专有的必填字段与校验逻辑。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#6-协议卡片保真与-take。
     *
     * @param rawValue 供应商返回的原始卡券类型整数
     * @return 匹配成功返回包含 {@link HuashengCardType} 的 Optional，未匹配返回 {@link Optional#empty()}
     */
    public static Optional<HuashengCardType> fromRawValue(int rawValue) {
        for (HuashengCardType type : values()) {
            if (type.rawValue == rawValue) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    int rawValue() {
        return rawValue;
    }
}
