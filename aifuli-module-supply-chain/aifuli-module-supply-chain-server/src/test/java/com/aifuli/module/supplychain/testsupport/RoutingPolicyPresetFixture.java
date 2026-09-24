package com.aifuli.module.supplychain.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 044 变更集预置的标准路由引导策略夹具。
 *
 * <p>Liquibase 变更集 044 在空库引导时预置 {@code STANDARD_PRODUCT_PRIMARY} 活动策略
 * （含受保护全局默认规则 GLOBAL_DEFAULT 的开放区间生效版本）。集成测试若需要发布
 * 自有路由策略（或直接插入活动策略行），必须先停用预置策略，为同类型让出唯一
 * active 位——数据库层由 {@code uk_sc_routing_policy_active (routing_type, active_flag)}
 * 唯一键、应用层由发布服务「同类型仅一条活动策略」校验共同强制。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#52-新表-ddl}。
 */
public final class RoutingPolicyPresetFixture {

    /** 044 预置引导策略编码，与变更集 SQL 保持一致。 */
    public static final String PRESET_POLICY_CODE = "STANDARD_PRODUCT_PRIMARY";

    private RoutingPolicyPresetFixture() {
    }

    /**
     * 停用预置引导策略：置 status=20（停用）、active_flag=NULL（满足
     * {@code ck_sc_routing_policy_active} 约束）。预置版本行保持已发布不动，
     * 停用后不再被活动策略查询选中。
     */
    public static void deactivatePresetPolicy(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "UPDATE sc_supplier_routing_policy SET status = 20, active_flag = NULL "
                        + "WHERE policy_code = ?",
                PRESET_POLICY_CODE);
    }
}
