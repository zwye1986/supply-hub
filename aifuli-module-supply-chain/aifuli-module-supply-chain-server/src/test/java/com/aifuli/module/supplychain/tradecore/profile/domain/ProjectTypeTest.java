package com.aifuli.module.supplychain.tradecore.profile.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 项目类型覆盖 PRD 8.3.1 六个当前值，并保留迁移前值用于历史数据兼容。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#421-项目列表数据字典}。
 */
class ProjectTypeTest {

    @Test
    void containsPrdAndLegacyCompatibilityValues() {
        Set<ProjectType> expected = EnumSet.of(
                ProjectType.HOLIDAY_BENEFIT,
                ProjectType.POINTS_REDEMPTION,
                ProjectType.EMPLOYEE_BENEFIT,
                ProjectType.CUSTOMER_REWARD,
                ProjectType.FLEXIBLE_BENEFIT,
                ProjectType.OTHER,
                ProjectType.WELFARE,
                ProjectType.BENEFIT,
                ProjectType.CAMPAIGN,
                ProjectType.SUBSIDY,
                ProjectType.GROUP);

        assertEquals(expected, EnumSet.allOf(ProjectType.class));
    }
}
