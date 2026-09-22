package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingRuleMapper;
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
 * 标准路由策略后继发布：关闭旧版本 valid_to 后以完整规则集发新版本。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#5-保存事务}。
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
            SupplierRoutingPolicyVersionMapper versionMapper =
                    context.getBean(SupplierRoutingPolicyVersionMapper.class);
            StandardProductRoutingRuleMapper ruleMapper =
                    context.getBean(StandardProductRoutingRuleMapper.class);
            SupplyChainDatabaseClock clock = context.getBean(SupplyChainDatabaseClock.class);

            LocalDateTime seedNow = clock.now().withNano(0);
            seedBuyerSkusAndSupplier(buyerService, catalogService, supplierMapper, seedNow);

            RoutingPolicyPublicationService.DraftResult firstDraft = publication.createDraft(
                    new RoutingPolicyPublicationService.CreateDraftCommand(
                            POLICY_CODE, POLICY_NAME, "首版单规则",
                            seedNow.minusHours(1), null,
                            List.of(ruleDraft("RR_SEED_1", "首条规则", "ROUTE_SUCC_100")), 1L));
            RoutingPolicyPublicationService.PublishResult firstPublished = publication.publish(
                    new RoutingPolicyPublicationService.PublishCommand(
                            firstDraft.policyVersionId(), 0L, "种子发布",
                            1L, "trace-succ-seed", null, null));
            assertThat(firstPublished.versionNo()).isEqualTo(1);

            SupplierRoutingPolicyVersionDO firstVersion =
                    versionMapper.selectById(firstPublished.policyVersionId());
            assertThat(firstVersion.getValidTo()).isNull();
            assertThat(firstVersion.getVersion()).isEqualTo(1L);

            LocalDateTime effectiveAt = clock.now();
            assertThat(effectiveAt).isAfter(firstVersion.getValidFrom());

            List<RoutingPolicyPublicationService.RuleDraft> successorRules = List.of(
                    ruleDraft("RR_SUCC_1", "后继规则一", "ROUTE_SUCC_100"),
                    ruleDraft("RR_SUCC_2", "后继规则二", "ROUTE_SUCC_200"));

            RoutingPolicyPublicationService.SuccessorPublishResult successor =
                    publication.publishSuccessorStandardProductRules(
                            new RoutingPolicyPublicationService.SuccessorPublishCommand(
                                    POLICY_CODE, POLICY_NAME, 1L, "trace-succ-publish",
                                    successorRules, firstPublished.policyVersionId(),
                                    firstVersion.getVersion(), effectiveAt));

            assertThat(successor.policyCode()).isEqualTo(POLICY_CODE);
            assertThat(successor.versionNo()).isEqualTo(2);
            assertThat(successor.policyVersionId()).isNotEqualTo(firstPublished.policyVersionId());

            SupplierRoutingPolicyVersionDO closed =
                    versionMapper.selectById(firstPublished.policyVersionId());
            assertThat(closed.getValidTo()).isEqualTo(effectiveAt);
            assertThat(closed.getStatus()).isEqualTo((short) 10);

            List<SupplierRoutingPolicyVersionDO> effective =
                    versionMapper.selectEffective(firstDraft.policyId(), effectiveAt);
            assertThat(effective).hasSize(1);
            assertThat(effective.getFirst().getId()).isEqualTo(successor.policyVersionId());
            assertThat(effective.getFirst().getVersionNo()).isEqualTo(2);
            assertThat(ruleMapper.selectByPolicyVersionId(successor.policyVersionId())).hasSize(2);

            // 既有草稿发布路径仍不自动收口 valid_to：未关闭当前生效版本时重叠发布须失败。
            RoutingPolicyPublicationService.DraftResult overlap = publication.createDraft(
                    new RoutingPolicyPublicationService.CreateDraftCommand(
                            POLICY_CODE, POLICY_NAME, "未收口重叠草稿",
                            effectiveAt, null, successorRules, 1L));
            assertThatThrownBy(() -> publication.publish(
                    new RoutingPolicyPublicationService.PublishCommand(
                            overlap.policyVersionId(), 0L, "非法重叠",
                            1L, "trace-succ-overlap", null, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("overlaps a published version");
        }
    }

    private static void seedBuyerSkusAndSupplier(
            BuyerCommandService buyerService, ProductCatalogCommandService catalog,
            SupplierMapper supplierMapper, LocalDateTime now) {
        buyerService.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                "WUSHENG_SUCC", "吾盛后继", "吾盛后继", null, null,
                "AIFULI_MALL", "tenant-routing-succ", now.minusMinutes(1), 1L));
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "SUCC_BRAND", "后继路由品牌", null, null, 0, 1L));
        catalog.changeBrandStatus("SUCC_BRAND", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "SUCC_DIRECT", "后继直充", null, 0, 1L));
        catalog.changeCategoryStatus("SUCC_DIRECT", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "SUCC_TELCO", "SUCC_BRAND", "SUCC_DIRECT",
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "后继话费直充", null, null, "中台受理后不可自动退款或撤销", 1L));
        catalog.changeSpuStatus("SUCC_TELCO", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "ROUTE_SUCC_100", "SUCC_TELCO", "后继话费直充 100 元",
                "DIRECT_RECHARGE", 10_000L, "CNY", 1L));
        catalog.changeSkuStatus("ROUTE_SUCC_100", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "ROUTE_SUCC_200", "SUCC_TELCO", "后继话费直充 200 元",
                "DIRECT_RECHARGE", 20_000L, "CNY", 1L));
        catalog.changeSkuStatus("ROUTE_SUCC_200", CatalogEntityStatus.ACTIVE, 1L);

        supplierMapper.insert(SupplierDO.builder()
                .supplierCode("QINAMENG_SUCC").supplierName("奇那梦后继").shortName("奇那梦后继")
                .supplierType("STANDARD_SUPPLIER").status(SupplierStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build());
    }

    private static RoutingPolicyPublicationService.RuleDraft ruleDraft(
            String ruleCode, String ruleName, String skuCode) {
        return new RoutingPolicyPublicationService.RuleDraft(
                ruleCode, ruleName, "WUSHENG_SUCC", skuCode, "DIRECT_RECHARGE", 10,
                List.of(optionalHealthCandidate("QINAMENG_SUCC")));
    }

    private static RoutingPolicyPublicationService.CandidateDraft optionalHealthCandidate(String supplierCode) {
        return new RoutingPolicyPublicationService.CandidateDraft(
                supplierCode, 0, "UNSUPPORTED", null,
                "UNSUPPORTED", null, "OPTIONAL", 60);
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
