package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingRuleDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StdRoutingRuleMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标准路由策略后继发布（PRD 四级范围模型）：关闭旧版本 valid_to 后以完整规则集发新版本，
 * 权重随版本演进同步到策略身份行。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#55-保存校验}。
 */
class RoutingPolicyPublicationSuccessorTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final String POLICY_CODE = "STANDARD_PRODUCT_PRIMARY";
    private static final String POLICY_NAME = "标准商品供给路由";

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
    void closesOldVersionThenPublishesSuccessorWithFullRuleSet() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BuyerCommandService buyerService = context.getBean(BuyerCommandService.class);
            ProductCatalogCommandService catalogService = context.getBean(ProductCatalogCommandService.class);
            RoutingPolicyPublicationService publication =
                    context.getBean(RoutingPolicyPublicationService.class);
            SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);
            SupplierRoutingPolicyMapper policyMapper =
                    context.getBean(SupplierRoutingPolicyMapper.class);
            SupplierRoutingPolicyVersionMapper versionMapper =
                    context.getBean(SupplierRoutingPolicyVersionMapper.class);
            StdRoutingRuleMapper ruleMapper = context.getBean(StdRoutingRuleMapper.class);
            SupplyChainDatabaseClock clock = context.getBean(SupplyChainDatabaseClock.class);

            LocalDateTime seedNow = clock.now().withNano(0);
            seedBuyerAndSuppliers(buyerService, supplierMapper, seedNow);

            // 044 预置引导：策略已带受保护全局默认规则的开放区间生效版本，后继发布接续它。
            SupplierRoutingPolicyDO policy = policyMapper.selectByCode(POLICY_CODE);
            assertThat(policy).isNotNull();
            List<SupplierRoutingPolicyVersionDO> effectiveBefore =
                    versionMapper.selectEffective(policy.getId(), seedNow);
            assertThat(effectiveBefore).hasSize(1);
            SupplierRoutingPolicyVersionDO firstVersion = effectiveBefore.getFirst();
            assertThat(firstVersion.getValidTo()).isNull();
            assertThat(firstVersion.getVersionNo()).isEqualTo(1);
            assertThat(ruleMapper.selectByPolicyVersionId(firstVersion.getId()))
                    .extracting(StdRoutingRuleDO::getRuleCode)
                    .containsExactly("GLOBAL_DEFAULT");

            LocalDateTime effectiveAt = clock.now();
            assertThat(effectiveAt).isAfter(firstVersion.getValidFrom());

            // 后继规则集：全局默认 + 分销商商品类型层级，避免启用集同范围冲突。
            List<RoutingPolicyPublicationService.RuleDraft> successorRules = List.of(
                    autoRule("RR_SUCC_1", "后继规则一"),
                    specifiedDistributorRule("RR_SUCC_2", "后继规则二", "WUSHENG_SUCC"));

            // 后继发布同时把权重 50/20/30 同步到策略身份行
            RoutingPolicyPublicationService.SuccessorPublishResult successor =
                    publication.publishSuccessorStandardProductRules(
                            new RoutingPolicyPublicationService.SuccessorPublishCommand(
                                    POLICY_CODE, POLICY_NAME, 1L, "trace-succ-publish",
                                    successorRules, firstVersion.getId(),
                                    firstVersion.getVersion(), effectiveAt,
                                    50, 20, 30));

            assertThat(successor.policyCode()).isEqualTo(POLICY_CODE);
            assertThat(successor.versionNo()).isEqualTo(2);
            assertThat(successor.policyVersionId()).isNotEqualTo(firstVersion.getId());

            SupplierRoutingPolicyVersionDO closed = versionMapper.selectById(firstVersion.getId());
            assertThat(closed.getValidTo()).isEqualTo(effectiveAt);
            assertThat(closed.getStatus()).isEqualTo((short) 10);

            List<SupplierRoutingPolicyVersionDO> effective =
                    versionMapper.selectEffective(policy.getId(), effectiveAt);
            assertThat(effective).hasSize(1);
            assertThat(effective.getFirst().getId()).isEqualTo(successor.policyVersionId());
            assertThat(effective.getFirst().getVersionNo()).isEqualTo(2);
            assertThat(ruleMapper.selectByPolicyVersionId(successor.policyVersionId())).hasSize(2);

            // 既有草稿发布路径仍不自动收口 valid_to：未关闭当前生效版本时重叠发布须失败。
            RoutingPolicyPublicationService.DraftResult overlap = publication.createDraft(
                    new RoutingPolicyPublicationService.CreateDraftCommand(
                            POLICY_CODE, POLICY_NAME, "未收口重叠草稿",
                            effectiveAt, null, successorRules, 1L,
                            50, 20, 30));
            assertThatThrownBy(() -> publication.publish(
                    new RoutingPolicyPublicationService.PublishCommand(
                            overlap.policyVersionId(), 0L, "非法重叠",
                            1L, "trace-succ-overlap", null, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("overlaps a published version");
        }
    }

    private static void seedBuyerAndSuppliers(
            BuyerCommandService buyerService, SupplierMapper supplierMapper, LocalDateTime now) {
        buyerService.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                "WUSHENG_SUCC", "吾盛后继", "吾盛后继", null, null,
                "AIFULI_MALL", "tenant-routing-succ", now.minusMinutes(1), 1L));
        // AUTO+全部供应商不落候选行；供应商仅为范围校验与后续人工配置预留
        supplierMapper.insert(SupplierDO.builder()
                .supplierCode("QINAMENG_SUCC").supplierName("奇那梦后继").shortName("奇那梦后继")
                .supplierType("STANDARD_SUPPLIER").status(SupplierStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build());
    }

    /** AUTO + 全部供应商规则：分销商/项目/商品类型/供应商四级范围全 ALL，无候选行。 */
    private static RoutingPolicyPublicationService.RuleDraft autoRule(String ruleCode, String ruleName) {
        return RoutingPolicyPublicationSuccessorOwnershipTest.autoAllSuppliersRule(ruleCode, ruleName);
    }

    /** AUTO + 分销商商品类型层级：验证 SPECIFIED 范围明细行落库。 */
    private static RoutingPolicyPublicationService.RuleDraft specifiedDistributorRule(
            String ruleCode, String ruleName, String buyerCode) {
        return new RoutingPolicyPublicationService.RuleDraft(
                ruleCode, ruleName, "AUTO", null,
                "SPECIFIED", List.of(buyerCode),
                "ALL", List.of(),
                "SPECIFIED", List.of("EQUITY_PRODUCT"),
                "ALL", List.of(),
                null,
                "STABLE", "PRICE", "COMPOSITE",
                100, (short) 10, false,
                List.of());
    }

    private ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE).properties(properties).run();
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
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxEventRepository.class, BuyerCommandService.class, ProductCatalogCommandService.class,
            ProductCenterTestConfiguration.class, RoutingPolicyPublicationService.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
