package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingCandidateDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingRuleDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingCandidateMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingRuleMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL/Liquibase 验证当前生效标准路由规则只读投影。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md#8-测试门禁}。
 */
class RoutingRuleAdminIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    @BeforeAll
    static void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void pageAndGetReadPublishedStandardRule() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            LocalDateTime now = LocalDateTime.now().withNano(0);
            BuyerDO buyer = BuyerDO.builder().buyerCode("BYR_ROUTE").buyerName("路由分销商")
                    .shortName("路由").buyerType("SAAS_MALL").status((short) 10).version(0L)
                    .createdAt(now).updatedAt(now).build();
            context.getBean(BuyerMapper.class).insert(buyer);
            SupplierDO first = supplier("SPL_ROUTE_A", "甲路由供应商", now);
            SupplierDO second = supplier("SPL_ROUTE_B", "乙路由供应商", now);
            SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);
            supplierMapper.insert(first);
            supplierMapper.insert(second);

            SupplierRoutingPolicyDO policy = SupplierRoutingPolicyDO.builder()
                    .policyCode("CARD_ADMIN_VIEW").policyName("管理端只读投影")
                    .routingType(RoutingType.STANDARD_PRODUCT.code())
                    .status((short) 10).activeFlag((short) 1).version(0L)
                    .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
            context.getBean(SupplierRoutingPolicyMapper.class).insert(policy);
            SupplierRoutingPolicyVersionDO version = SupplierRoutingPolicyVersionDO.builder()
                    .policyId(policy.getId()).versionNo(1).versionName("生效版")
                    .validFrom(now.minusHours(1)).configDigest(new byte[32])
                    .status((short) 10).publishReason("admin-readonly")
                    .publishedBy(1L).publishedAt(now).version(0L)
                    .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
            context.getBean(SupplierRoutingPolicyVersionMapper.class).insert(version);
            StandardProductRoutingRuleDO rule = StandardProductRoutingRuleDO.builder()
                    .policyVersionId(version.getId()).ruleCode("RR_ADMIN_1")
                    .ruleName("管理端双候选").buyerId(buyer.getId())
                    .fulfillmentType("DIRECT_RECHARGE").rulePriority(10).version(0L)
                    .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
            context.getBean(StandardProductRoutingRuleMapper.class).insert(rule);
            StandardProductRoutingCandidateMapper candidateMapper =
                    context.getBean(StandardProductRoutingCandidateMapper.class);
            candidateMapper.insert(candidate(rule.getId(), first.getId(), 0, now));
            candidateMapper.insert(candidate(rule.getId(), second.getId(), 10, now));

            RoutingRuleAdminQueryService service = context.getBean(RoutingRuleAdminQueryService.class);
            var page = service.page(new RoutingRuleAdminQueryService.RoutingRulePageQuery(
                    null, List.of(), List.of(), List.of(), List.of(), null, List.of(), null, null, 1, 20));
            assertThat(page.getList()).hasSize(1);
            assertThat(page.getList().getFirst().ruleCode()).isEqualTo("RR_ADMIN_1");
            assertThat(page.getList().getFirst().routeMode()).isEqualTo(RoutingRuleRouteMode.AUTO);
            assertThat(page.getList().getFirst().supplierCodes()).containsExactly("SPL_ROUTE_A", "SPL_ROUTE_B");
            assertThat(page.getList().getFirst().distributorCodes()).containsExactly("BYR_ROUTE");
            assertThat(page.getList().getFirst().priorities().getFirst().strategy())
                    .isEqualTo(RoutingRuleStrategy.STABLE);
            var detail = service.get("RR_ADMIN_1");
            assertThat(detail.name()).isEqualTo("管理端双候选");
            assertThat(detail.supplierNames()).containsExactly("甲路由供应商", "乙路由供应商");
        }
    }

    private static SupplierDO supplier(String code, String name, LocalDateTime now) {
        return SupplierDO.builder().supplierCode(code).supplierName(name).shortName(name)
                .supplierType("STANDARD_SUPPLIER").status(SupplierStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
    }

    private static StandardProductRoutingCandidateDO candidate(Long ruleId, Long supplierId,
                                                               int priority, LocalDateTime now) {
        return StandardProductRoutingCandidateDO.builder().ruleId(ruleId).supplierId(supplierId)
                .candidatePriority(priority).inventoryCheckMode("UNSUPPORTED")
                .balanceCheckMode("UNSUPPORTED").healthCheckMode("OPTIONAL").healthMaxAgeSeconds(60)
                .version(0L).createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
    }

    private ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String username() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root") : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "") : MYSQL.getPassword();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, RoutingRuleAdminQueryService.class})
    static class TestConfiguration {
    }
}
