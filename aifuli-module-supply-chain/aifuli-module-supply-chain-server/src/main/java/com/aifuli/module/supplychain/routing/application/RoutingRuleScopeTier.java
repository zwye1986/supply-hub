package com.aifuli.module.supplychain.routing.application;

import java.util.Arrays;

/**
 * PRD 标准商品路由的五级适用范围形状及固定优先级。
 *
 * <p>只根据四个范围模式分类；实际编码命中由路由应用服务判断。供应商指定范围表示
 * 规则已圈定候选供应商，订单在选路前没有供应商输入。保存与发布共用分类，防止生成
 * 无法进入五级匹配的规则。设计见
 * {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#3-规则匹配}。</p>
 */
public enum RoutingRuleScopeTier {
    FOUR_DIMENSION(RoutingRuleScopeMode.SPECIFIED, RoutingRuleScopeMode.SPECIFIED,
            RoutingRuleScopeMode.SPECIFIED, RoutingRuleScopeMode.SPECIFIED),
    DISTRIBUTOR_PRODUCT_TYPE(RoutingRuleScopeMode.SPECIFIED, RoutingRuleScopeMode.ALL,
            RoutingRuleScopeMode.SPECIFIED, RoutingRuleScopeMode.ALL),
    PROJECT_PRODUCT_TYPE(RoutingRuleScopeMode.ALL, RoutingRuleScopeMode.SPECIFIED,
            RoutingRuleScopeMode.SPECIFIED, RoutingRuleScopeMode.ALL),
    PRODUCT_TYPE(RoutingRuleScopeMode.ALL, RoutingRuleScopeMode.ALL,
            RoutingRuleScopeMode.SPECIFIED, RoutingRuleScopeMode.ALL),
    GLOBAL_DEFAULT(RoutingRuleScopeMode.ALL, RoutingRuleScopeMode.ALL,
            RoutingRuleScopeMode.ALL, RoutingRuleScopeMode.ALL);

    private final RoutingRuleScopeMode distributor;
    private final RoutingRuleScopeMode project;
    private final RoutingRuleScopeMode productType;
    private final RoutingRuleScopeMode supplier;

    RoutingRuleScopeTier(RoutingRuleScopeMode distributor, RoutingRuleScopeMode project,
                         RoutingRuleScopeMode productType, RoutingRuleScopeMode supplier) {
        this.distributor = distributor;
        this.project = project;
        this.productType = productType;
        this.supplier = supplier;
    }

    /** 返回固定层级；其它范围组合没有 PRD 运行语义，保存和发布都须拒绝。 */
    public static RoutingRuleScopeTier require(RoutingRuleScopeMode distributor,
                                               RoutingRuleScopeMode project,
                                               RoutingRuleScopeMode productType,
                                               RoutingRuleScopeMode supplier) {
        return Arrays.stream(values())
                .filter(tier -> tier.distributor == distributor && tier.project == project
                        && tier.productType == productType && tier.supplier == supplier)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("路由规则范围组合不属于 PRD 五级匹配层级"));
    }

    /** 发布器与运行时使用库内字符串模式，严格解析后复用相同分类。 */
    public static RoutingRuleScopeTier require(String distributor, String project,
                                               String productType, String supplier) {
        return require(RoutingRuleScopeMode.valueOf(distributor),
                RoutingRuleScopeMode.valueOf(project),
                RoutingRuleScopeMode.valueOf(productType),
                RoutingRuleScopeMode.valueOf(supplier));
    }
}
