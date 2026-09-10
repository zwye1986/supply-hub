package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * 项目类型封闭枚举。
 *
 * <p>前六项是 PRD 8.3.1 当前可选值；后五项只兼容历史数据，不进入 system 数据字典。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#421-项目列表数据字典}。</p>
 */
public enum ProjectType {
    HOLIDAY_BENEFIT,
    POINTS_REDEMPTION,
    EMPLOYEE_BENEFIT,
    CUSTOMER_REWARD,
    FLEXIBLE_BENEFIT,
    OTHER,

    /** @deprecated 历史项目类型，仅兼容已有数据。 */
    @Deprecated
    WELFARE,
    /** @deprecated 历史项目类型，仅兼容已有数据。 */
    @Deprecated
    BENEFIT,
    /** @deprecated 历史项目类型，仅兼容已有数据。 */
    @Deprecated
    CAMPAIGN,
    /** @deprecated 历史项目类型，仅兼容已有数据。 */
    @Deprecated
    SUBSIDY,
    /** @deprecated 历史项目类型，仅兼容已有数据。 */
    @Deprecated
    GROUP
}
