package com.aifuli.module.supplychain.routing.application;

import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.routing.application.RoutingRuleScopeMode.ALL;
import static com.aifuli.module.supplychain.routing.application.RoutingRuleScopeMode.SPECIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 六级范围形状的纯逻辑门禁。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#3-规则匹配}。
 */
class RoutingRuleScopeTierTest {

    @Test
    void classifiesOnlyPrdTiersInDescendingPrecedence() {
        assertThat(RoutingRuleScopeTier.values()).extracting(Enum::name).containsExactly(
                "FOUR_DIMENSION", "DISTRIBUTOR_PRODUCT_TYPE", "PROJECT_PRODUCT_TYPE",
                "PRODUCT_TYPE", "DISTRIBUTOR_PROJECT", "GLOBAL_DEFAULT");
        assertThat(RoutingRuleScopeTier.require(SPECIFIED, SPECIFIED, SPECIFIED, SPECIFIED))
                .isEqualTo(RoutingRuleScopeTier.FOUR_DIMENSION);
        assertThat(RoutingRuleScopeTier.require(SPECIFIED, ALL, SPECIFIED, ALL))
                .isEqualTo(RoutingRuleScopeTier.DISTRIBUTOR_PRODUCT_TYPE);
        assertThat(RoutingRuleScopeTier.require(ALL, SPECIFIED, SPECIFIED, ALL))
                .isEqualTo(RoutingRuleScopeTier.PROJECT_PRODUCT_TYPE);
        assertThat(RoutingRuleScopeTier.require(ALL, ALL, SPECIFIED, ALL))
                .isEqualTo(RoutingRuleScopeTier.PRODUCT_TYPE);
        assertThat(RoutingRuleScopeTier.require(SPECIFIED, SPECIFIED, ALL, ALL))
                .extracting(Enum::name).isEqualTo("DISTRIBUTOR_PROJECT");
        assertThat(RoutingRuleScopeTier.require("ALL", "ALL", "ALL", "ALL"))
                .isEqualTo(RoutingRuleScopeTier.GLOBAL_DEFAULT);
    }

    @Test
    void rejectsShapesThatWouldNeverMatch() {
        assertThatThrownBy(() -> RoutingRuleScopeTier.require(SPECIFIED, ALL, ALL, ALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("六级匹配层级");
        assertThatThrownBy(() -> RoutingRuleScopeTier.require(ALL, ALL, ALL, SPECIFIED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("六级匹配层级");
        assertThatThrownBy(() -> RoutingRuleScopeTier.require(SPECIFIED, SPECIFIED, SPECIFIED, ALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("六级匹配层级");
    }
}
