package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingRuleDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingRuleScopeDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 已发布规则裁决的纯逻辑测试：固定六级顺序、停用跳过、同层裁决和默认兜底。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#3-规则匹配}。
 */
class SupplierRoutingRuleMatchingTest {

    private static final String BUYER = "BUYER_A";
    private static final String PROJECT = "PROJECT_A";
    private static final String PRODUCT_TYPE = "EQUITY_PRODUCT";

    @Test
    void fourDimensionWinsBeforeOtherTiersRegardlessOfRulePriority() {
        StdRoutingRuleDO full = rule(1L, "FULL", "SPECIFIED", "SPECIFIED",
                "SPECIFIED", "SPECIFIED", 100, (short) 10);
        StdRoutingRuleDO buyerType = rule(2L, "BUYER_TYPE", "SPECIFIED", "ALL",
                "SPECIFIED", "ALL", 0, (short) 10);
        StdRoutingRuleDO projectType = rule(3L, "PROJECT_TYPE", "ALL", "SPECIFIED",
                "SPECIFIED", "ALL", 0, (short) 10);
        StdRoutingRuleDO type = rule(4L, "TYPE", "ALL", "ALL",
                "SPECIFIED", "ALL", 0, (short) 10);
        StdRoutingRuleDO global = rule(5L, "GLOBAL_DEFAULT", "ALL", "ALL",
                "ALL", "ALL", 0, (short) 10);
        assertThat(select(List.of(global, type, projectType, buyerType, full))).isEqualTo(full);
    }

    @Test
    void disabledRuleIsSkippedBeforeTryingSameAndLowerTiers() {
        StdRoutingRuleDO disabledFull = rule(1L, "FULL", "SPECIFIED", "SPECIFIED",
                "SPECIFIED", "SPECIFIED", 0, (short) 20);
        StdRoutingRuleDO buyerType = rule(2L, "BUYER_TYPE", "SPECIFIED", "ALL",
                "SPECIFIED", "ALL", 20, (short) 10);
        StdRoutingRuleDO buyerTypeFirst = rule(3L, "BUYER_TYPE_FIRST", "SPECIFIED", "ALL",
                "SPECIFIED", "ALL", 10, (short) 10);
        StdRoutingRuleDO type = rule(4L, "TYPE", "ALL", "ALL",
                "SPECIFIED", "ALL", 0, (short) 10);
        assertThat(select(List.of(type, buyerType, disabledFull, buyerTypeFirst)))
                .isEqualTo(buyerTypeFirst);
    }

    @Test
    void projectThenProductTypeThenEnabledGlobalFallback() {
        StdRoutingRuleDO projectType = rule(1L, "PROJECT_TYPE", "ALL", "SPECIFIED",
                "SPECIFIED", "ALL", 0, (short) 10);
        StdRoutingRuleDO type = rule(2L, "TYPE", "ALL", "ALL",
                "SPECIFIED", "ALL", 0, (short) 10);
        StdRoutingRuleDO global = rule(3L, "GLOBAL_DEFAULT", "ALL", "ALL",
                "ALL", "ALL", 0, (short) 10);
        List<StdRoutingRuleDO> rules = List.of(global, type, projectType);
        List<StdRoutingRuleScopeDO> scopes = scopesFor(rules);
        assertThat(SupplierRoutingApplicationService.selectMatchedRule(
                rules, scopes, "OTHER", PROJECT, PRODUCT_TYPE)).isEqualTo(projectType);
        assertThat(SupplierRoutingApplicationService.selectMatchedRule(
                rules, scopes, "OTHER", "OTHER", PRODUCT_TYPE)).isEqualTo(type);
        assertThat(SupplierRoutingApplicationService.selectMatchedRule(
                rules, scopes, "OTHER", "OTHER", "OTHER")).isEqualTo(global);
        global.setStatus((short) 20);
        assertThatThrownBy(() -> SupplierRoutingApplicationService.selectMatchedRule(
                rules, scopes, "OTHER", "OTHER", "OTHER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matched enabled rule");
    }

    @Test
    void productTypeRuleWinsOverDistributorProjectRule() {
        StdRoutingRuleDO type = rule(1L, "TYPE", "ALL", "ALL",
                "SPECIFIED", "ALL", 100, (short) 10);
        StdRoutingRuleDO buyerProject = rule(2L, "BUYER_PROJECT", "SPECIFIED", "SPECIFIED",
                "ALL", "ALL", 0, (short) 10);
        assertThat(select(List.of(buyerProject, type))).isEqualTo(type);
    }

    @Test
    void distributorProjectRuleMatchesAnyProductTypeBeforeGlobalFallback() {
        StdRoutingRuleDO buyerProject = rule(1L, "BUYER_PROJECT", "SPECIFIED", "SPECIFIED",
                "ALL", "ALL", 100, (short) 10);
        StdRoutingRuleDO global = rule(2L, "GLOBAL_DEFAULT", "ALL", "ALL",
                "ALL", "ALL", 0, (short) 10);
        List<StdRoutingRuleDO> rules = List.of(global, buyerProject);
        List<StdRoutingRuleScopeDO> scopes = scopesFor(rules);
        assertThat(SupplierRoutingApplicationService.selectMatchedRule(
                rules, scopes, BUYER, PROJECT, "OTHER")).isEqualTo(buyerProject);
        assertThat(SupplierRoutingApplicationService.selectMatchedRule(
                rules, scopes, BUYER, "OTHER", "OTHER")).isEqualTo(global);
        buyerProject.setStatus((short) 20);
        assertThat(SupplierRoutingApplicationService.selectMatchedRule(
                rules, scopes, BUYER, PROJECT, "OTHER")).isEqualTo(global);
    }

    @Test
    void invalidPublishedScopeFailsClosed() {
        StdRoutingRuleDO unsupported = rule(1L, "BUYER_ONLY", "SPECIFIED", "ALL",
                "ALL", "ALL", 0, (short) 10);
        assertThatThrownBy(() -> select(List.of(unsupported)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported scope");
    }

    private static StdRoutingRuleDO select(List<StdRoutingRuleDO> rules) {
        return SupplierRoutingApplicationService.selectMatchedRule(
                rules, scopesFor(rules), BUYER, PROJECT, PRODUCT_TYPE);
    }

    private static List<StdRoutingRuleScopeDO> scopesFor(List<StdRoutingRuleDO> rules) {
        return rules.stream().flatMap(rule -> {
            java.util.ArrayList<StdRoutingRuleScopeDO> result = new java.util.ArrayList<>();
            if ("SPECIFIED".equals(rule.getDistributorMode())) {
                result.add(scope(rule.getId(), "DISTRIBUTOR", BUYER));
            }
            if ("SPECIFIED".equals(rule.getProjectMode())) {
                result.add(scope(rule.getId(), "PROJECT", PROJECT));
            }
            if ("SPECIFIED".equals(rule.getProductTypeMode())) {
                result.add(scope(rule.getId(), "PRODUCT_TYPE", PRODUCT_TYPE));
            }
            return result.stream();
        }).toList();
    }

    private static StdRoutingRuleScopeDO scope(Long ruleId, String kind, String code) {
        return StdRoutingRuleScopeDO.builder()
                .ruleId(ruleId).scopeKind(kind).scopeCode(code).build();
    }

    private static StdRoutingRuleDO rule(Long id, String code, String distributor,
                                         String project, String productType, String supplier,
                                         int priority, short status) {
        return StdRoutingRuleDO.builder().id(id).ruleCode(code)
                .distributorMode(distributor).projectMode(project)
                .productTypeMode(productType).supplierMode(supplier)
                .rulePriority(priority).status(status).build();
    }
}
