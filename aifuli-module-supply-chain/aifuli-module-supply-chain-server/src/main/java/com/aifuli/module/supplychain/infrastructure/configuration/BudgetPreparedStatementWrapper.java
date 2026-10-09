package com.aifuli.module.supplychain.infrastructure.configuration;

/**
 * 已作废（Druid 组件已完全移除）。
 *
 * @deprecated 业务链路已改由 Hikari + 标准 JDBC 预算控制链路（StandardBudgetRoutingDataSource 等）接管，严禁调用。
 * 原实现已归档于 {@code docs/superpowers/specs/archive/deprecated-druid/BudgetPreparedStatementWrapper.java}。
 */
@Deprecated(since = "2026-10-09", forRemoval = true)
public class BudgetPreparedStatementWrapper {

    public static final String DEPRECATION_NOTICE =
            "该类已作废：Druid 组件已完全移除，业务链路改由 Hikari 标准数据源接管，严禁调用。"
            + "详见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md";

    public BudgetPreparedStatementWrapper() {
        throw new UnsupportedOperationException(DEPRECATION_NOTICE);
    }
}
