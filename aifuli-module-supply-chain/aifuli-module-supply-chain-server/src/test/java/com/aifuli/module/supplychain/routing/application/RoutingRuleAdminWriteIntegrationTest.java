package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRuleView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.WeightInput;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.InventorySourceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.InventorySourceStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
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

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 验证路由规则页补丁后继发布（PRD 四级范围模型）：
 * 候选顺序变更生效、路由选中随候选优先级变化、删除最后一条启用规则拒绝。
 *
 * <p>两条候选 offer 同价同评分（无稳定性评分），三策略比较全部打平，
 * 由候选优先级稳定裁决，从而隔离验证「补丁后候选顺序」对路由结果的影响。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#55}。</p>
 */
class RoutingRuleAdminWriteIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final String POLICY_CODE = "STANDARD_PRODUCT_PRIMARY";
    private static final String POLICY_NAME = "标准商品供给路由";
    private static final String BUYER_CODE = "WUSHENG_WRITE";
    private static final String PROJECT_CODE = "WRITE_PROJECT";
    private static final String PRODUCT_TYPE_CODE = "EQUITY_PRODUCT";
    private static final String SKU_200 = "ROUTE_WRITE_200";
    private static final String SUPPLIER_A = "QINAMENG_WRITE";
    private static final String SUPPLIER_B = "ZETA_WRITE";
    private static final String RULE_1 = "RR_WRITE_1";
    private static final String RULE_2 = "RR_WRITE_2";
    private static final Long OPERATOR_ID = 2016L;

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
    void updateSwapsCandidateOrderThenRouteFollowsNewPriorityAndDeleteLastRejected() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BuyerCommandService buyerService = context.getBean(BuyerCommandService.class);
            ProductCatalogCommandService catalogService = context.getBean(ProductCatalogCommandService.class);
            RoutingPolicyPublicationService publication =
                    context.getBean(RoutingPolicyPublicationService.class);
            RoutingRuleAdminCommandService commandService =
                    context.getBean(RoutingRuleAdminCommandService.class);
            RoutingRuleAdminQueryService queryService =
                    context.getBean(RoutingRuleAdminQueryService.class);
            SupplierRoutingApplicationService routing =
                    context.getBean(SupplierRoutingApplicationService.class);
            SupplierRoutingPolicyVersionMapper versionMapper =
                    context.getBean(SupplierRoutingPolicyVersionMapper.class);
            SupplyChainDatabaseClockAdapter clock = new SupplyChainDatabaseClockAdapter(context);
            BuyerMapper buyerMapper = context.getBean(BuyerMapper.class);
            BuyerProjectMapper projectMapper = context.getBean(BuyerProjectMapper.class);
            PlatformSkuMapper skuMapper = context.getBean(PlatformSkuMapper.class);
            SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);
            CredentialSlotMapper credentialMapper = context.getBean(CredentialSlotMapper.class);
            SupplierConnectorMapper connectorMapper = context.getBean(SupplierConnectorMapper.class);
            SupplierConnectorVersionMapper connectorVersionMapper =
                    context.getBean(SupplierConnectorVersionMapper.class);
            InventorySourceMapper inventoryMapper = context.getBean(InventorySourceMapper.class);
            SupplierOfferMapper offerMapper = context.getBean(SupplierOfferMapper.class);
            SupplierOfferVersionMapper offerVersionMapper =
                    context.getBean(SupplierOfferVersionMapper.class);

            LocalDateTime seedNow = clock.now().withNano(0);
            seedBuyerAndSku(buyerService, catalogService, seedNow);
            BuyerDO buyer = buyerMapper.selectByBuyerCode(BUYER_CODE);
            BuyerProjectDO project = BuyerProjectDO.builder()
                    .projectCode(PROJECT_CODE).projectName("写测路由项目").buyerId(buyer.getId())
                    .catalogMode("MANAGED").settlementPartyType("DISTRIBUTOR")
                    .status((short) 10).version(0L).createdBy(1L).createdAt(seedNow)
                    .updatedBy(1L).updatedAt(seedNow).build();
            projectMapper.insert(project);
            PlatformSkuDO sku200 = skuMapper.selectByCode(SKU_200);
            // 双候选同价（500 分）且无评分：三策略打平，候选优先级裁决。
            createOfferFixture(SUPPLIER_A, "奇那梦写测", 500L, sku200, seedNow,
                    supplierMapper, credentialMapper, connectorMapper, connectorVersionMapper,
                    inventoryMapper, offerMapper, offerVersionMapper);
            createOfferFixture(SUPPLIER_B, "后备写测", 500L, sku200, seedNow,
                    supplierMapper, credentialMapper, connectorMapper, connectorVersionMapper,
                    inventoryMapper, offerMapper, offerVersionMapper);

            // 044 预置引导策略已带开放区间生效版本（GLOBAL_DEFAULT），写测夹具接续其后继发布
            // 首组业务规则（版本 2）：与管理端真实首笔操作同路径，避免与预置版本区间重叠。
            SupplierRoutingPolicyMapper policyMapper = context.getBean(SupplierRoutingPolicyMapper.class);
            SupplierRoutingPolicyDO presetPolicy = policyMapper.selectByCode(POLICY_CODE);
            assertThat(presetPolicy).isNotNull();
            SupplierRoutingPolicyVersionDO presetVersion = versionMapper
                    .selectEffective(presetPolicy.getId(), seedNow).getFirst();
            RoutingPolicyPublicationService.SuccessorPublishResult published =
                    publication.publishSuccessorStandardProductRules(
                            new RoutingPolicyPublicationService.SuccessorPublishCommand(
                                    POLICY_CODE, POLICY_NAME, 1L, "trace-write-seed",
                                    List.of(disabledRule(RULE_1, "写测规则一"),
                                            enabledRule(RULE_2, "写测规则二")),
                                    presetVersion.getId(), presetVersion.getVersion(), seedNow,
                                    40, 30, 30));
            assertThat(published.versionNo()).isEqualTo(2);
            Long firstVersionId = published.policyVersionId();

            RoutingRuleView before = queryService.get(RULE_2);
            assertThat(before.supplierCodes()).containsExactly(SUPPLIER_A, SUPPLIER_B);

            RoutingInput routeInput = new RoutingInput(buyer.getId(), project.getId(), sku200.getId(),
                    "DIRECT_RECHARGE", 1L, clock.now(), Map.of());
            SupplierRoutingApplicationService.RoutingSnapshot beforeRoute = routing.route(routeInput);
            assertThat(beforeRoute.decision().selectedCandidate().supplierCode()).isEqualTo(SUPPLIER_A);
            assertThat(beforeRoute.decision().reasonCode())
                    .isEqualTo("SELECTED_BY_STRATEGY_STABLE_PRICE_COMPOSITE");

            LocalDateTime beforeUpdate = clock.now();
            NormalizedRulePatch swapPatch = new NormalizedRulePatch(
                    RULE_2, "写测规则二", RoutingRuleRouteMode.AUTO, RoutingRuleStatus.ENABLED,
                    RoutingRuleScopeMode.SPECIFIED, List.of(BUYER_CODE),
                    RoutingRuleScopeMode.SPECIFIED, List.of(PROJECT_CODE),
                    RoutingRuleScopeMode.SPECIFIED, List.of(PRODUCT_TYPE_CODE),
                    RoutingRuleScopeMode.SPECIFIED, List.of(SUPPLIER_B, SUPPLIER_A),
                    null,
                    List.of(RoutingRuleStrategy.STABLE, RoutingRuleStrategy.PRICE,
                            RoutingRuleStrategy.COMPOSITE),
                    new WeightInput(40, 30, 30), null);
            RoutingRuleView updatedView = commandService.update(RULE_2, swapPatch, OPERATOR_ID);
            assertThat(updatedView.ruleCode()).isEqualTo(RULE_2);
            assertThat(updatedView.supplierCodes()).containsExactly(SUPPLIER_B, SUPPLIER_A);

            LocalDateTime afterUpdateAt = clock.now();
            SupplierRoutingPolicyVersionDO closed = versionMapper.selectById(firstVersionId);
            assertThat(closed.getValidTo()).isNotNull();
            assertThat(closed.getValidTo()).isAfterOrEqualTo(beforeUpdate.withNano(0));
            assertThat(closed.getValidTo()).isBeforeOrEqualTo(afterUpdateAt);

            List<SupplierRoutingPolicyVersionDO> effective =
                    versionMapper.selectEffective(presetPolicy.getId(), afterUpdateAt);
            assertThat(effective).hasSize(1);
            assertThat(effective.getFirst().getId()).isNotEqualTo(firstVersionId);
            // 预置版本 1 → 夹具后继版本 2 → 管理端补丁版本 3
            assertThat(effective.getFirst().getVersionNo()).isEqualTo(3);
            assertThat(effective.getFirst().getValidFrom()).isEqualTo(closed.getValidTo());

            SupplierRoutingApplicationService.RoutingSnapshot afterRoute = routing.route(
                    new RoutingInput(buyer.getId(), project.getId(), sku200.getId(), "DIRECT_RECHARGE",
                            1L, afterUpdateAt, Map.of()));
            assertThat(afterRoute.policyVersionId()).isEqualTo(effective.getFirst().getId());
            assertThat(afterRoute.decision().selected()).isTrue();
            assertThat(afterRoute.decision().selectedCandidate())
                    .extracting(RoutingCandidate::supplierCode)
                    .isEqualTo(SUPPLIER_B);
            assertThat(afterRoute.decision().orderedCandidates())
                    .extracting(RoutingCandidate::supplierCode)
                    .containsExactly(SUPPLIER_B, SUPPLIER_A);
            assertThat(afterRoute.decision().reasonCode())
                    .isEqualTo("SELECTED_BY_STRATEGY_STABLE_PRICE_COMPOSITE");

            commandService.delete(RULE_1, OPERATOR_ID);
            assertThatThrownBy(() -> queryService.get(RULE_1))
                    .isInstanceOf(ServiceException.class);
            assertThat(queryService.get(RULE_2).ruleCode()).isEqualTo(RULE_2);

            // 删除最后一条规则拒绝：空规则集不允许发布。
            assertThatThrownBy(() -> commandService.delete(RULE_2, OPERATOR_ID))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(ROUTING_RULE_NOT_FOUND.getCode());
        }
    }

    /** 停用规则：不参与匹配与启用集范围唯一性校验，仅验证可被删除。 */
    private static RoutingPolicyPublicationService.RuleDraft disabledRule(
            String ruleCode, String ruleName) {
        return new RoutingPolicyPublicationService.RuleDraft(
                ruleCode, ruleName, "AUTO", null,
                "SPECIFIED", List.of(BUYER_CODE),
                "SPECIFIED", List.of(PROJECT_CODE),
                "SPECIFIED", List.of(PRODUCT_TYPE_CODE),
                "SPECIFIED", List.of(SUPPLIER_A, SUPPLIER_B),
                null, "STABLE", "PRICE", "COMPOSITE",
                5, (short) 20, false,
                List.of(candidate(SUPPLIER_A, 0), candidate(SUPPLIER_B, 1)));
    }

    /** 启用规则：四维组合，AUTO+SPECIFIED 双候选。 */
    private static RoutingPolicyPublicationService.RuleDraft enabledRule(
            String ruleCode, String ruleName) {
        return new RoutingPolicyPublicationService.RuleDraft(
                ruleCode, ruleName, "AUTO", null,
                "SPECIFIED", List.of(BUYER_CODE),
                "SPECIFIED", List.of(PROJECT_CODE),
                "SPECIFIED", List.of(PRODUCT_TYPE_CODE),
                "SPECIFIED", List.of(SUPPLIER_A, SUPPLIER_B),
                null, "STABLE", "PRICE", "COMPOSITE",
                10, (short) 10, false,
                List.of(candidate(SUPPLIER_A, 0), candidate(SUPPLIER_B, 1)));
    }

    private static void seedBuyerAndSku(
            BuyerCommandService buyerService, ProductCatalogCommandService catalog, LocalDateTime now) {
        buyerService.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                BUYER_CODE, "吾盛写测", "吾盛写测", null, null,
                "AIFULI_MALL", "tenant-routing-write", now.minusMinutes(1), 1L));
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "WRITE_BRAND", "写测路由品牌", null, null, 0, 1L));
        catalog.changeBrandStatus("WRITE_BRAND", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "WRITE_DIRECT", "写测直充", null, 0, 1L));
        catalog.changeCategoryStatus("WRITE_DIRECT", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "WRITE_TELCO", "WRITE_BRAND", "WRITE_DIRECT",
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "写测话费直充", null, null, "中台受理后不可自动退款或撤销", 1L));
        catalog.changeSpuStatus("WRITE_TELCO", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                SKU_200, "WRITE_TELCO", "写测话费直充 200 元",
                "DIRECT_RECHARGE", 20_000L, "CNY", 1L));
        catalog.changeSkuStatus(SKU_200, CatalogEntityStatus.ACTIVE, 1L);
    }

    private static void createOfferFixture(
            String supplierCode, String supplierName, long price, PlatformSkuDO sku, LocalDateTime now,
            SupplierMapper supplierMapper, CredentialSlotMapper credentialMapper,
            SupplierConnectorMapper connectorMapper, SupplierConnectorVersionMapper connectorVersionMapper,
            InventorySourceMapper inventoryMapper, SupplierOfferMapper offerMapper,
            SupplierOfferVersionMapper offerVersionMapper) {
        SupplierDO supplier = SupplierDO.builder()
                .supplierCode(supplierCode).supplierName(supplierName).shortName(supplierName)
                .supplierType("STANDARD_SUPPLIER").status(SupplierStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        supplierMapper.insert(supplier);
        CredentialSlotDO credential = CredentialSlotDO.builder()
                .slotNo("CRD_WRITE_" + supplierCode).providerType("ENV")
                .secretRef("env:WRITE_" + supplierCode).maskedHint("fixture-****")
                .activeSecretVersion("v1").status((short) 10).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        credentialMapper.insert(credential);
        SupplierConnectorDO connector = SupplierConnectorDO.builder()
                .connectorNo("CON_WRITE_" + supplierCode).supplierId(supplier.getId())
                .connectorCode("PRIMARY").adapterType("QNM_GAME_ORDER_V1")
                .status((short) 10).version(0L).createdBy(1L).createdAt(now)
                .updatedBy(1L).updatedAt(now).build();
        connectorMapper.insert(connector);
        SupplierConnectorVersionDO connectorVersion = SupplierConnectorVersionDO.builder()
                .connectorId(connector.getId()).versionNo(1).baseUrl("https://fixture.invalid")
                .merchantAccount("fixture").credentialSlotId(credential.getId()).protocolVersion("V1")
                .callbackPath("/fixture/callback").connectTimeoutMs(1_000).readTimeoutMs(3_000)
                .rateLimit(10).concurrencyLimit(2).adapterConfigSchemaVersion(1)
                .adapterConfigJson("{}").validationStatus((short) 10).validatedAt(now)
                .validationExpiresAt(now.plusDays(1)).publishedAt(now).configDigest(new byte[32])
                .status(ConnectorVersionStatus.PUBLISHED.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        connectorVersionMapper.insert(connectorVersion);
        connector.setCurrentVersionId(connectorVersion.getId());
        connector.setUpdatedAt(now);
        assertThat(connectorMapper.updateById(connector)).isEqualTo(1);

        InventorySourceDO inventory = InventorySourceDO.builder()
                .sourceNo("INV_WRITE_" + supplierCode).supplierId(supplier.getId())
                .platformSkuId(sku.getId()).sourceType("REMOTE_ON_DEMAND")
                .externalSourceCode("REMOTE_" + supplierCode).ownerType("SUPPLIER")
                .status(InventorySourceStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        inventoryMapper.insert(inventory);
        SupplierOfferDO offer = SupplierOfferDO.builder()
                .offerNo("OFR_WRITE_" + supplierCode).offerCode("TELCO_WRITE")
                .supplierId(supplier.getId()).platformSkuId(sku.getId())
                .status(OfferStatus.PUBLISHED.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        offerMapper.insert(offer);
        SupplierOfferVersionDO offerVersion = SupplierOfferVersionDO.builder()
                .offerId(offer.getId()).versionNo(1).externalSkuCode("EXT-" + offer.getId())
                .supplierSettlementUnitPrice(price).currency("CNY")
                .connectorVersionId(connectorVersion.getId()).inventorySourceId(inventory.getId())
                .minQuantity(1L).maxQuantity(1L).quantityStep(1L)
                .primaryCapabilityCode("CREATE_RECHARGE").completionMode("QUERY_OR_CALLBACK")
                .createIdempotencyMode("DEDUP_ONLY").unknownResultPolicy("QUERY_ORDER")
                .partialDeliveryMode("FORBIDDEN").resultMultiplicity("NONE")
                .fulfillmentConfigSchemaVersion(1).fulfillmentConfigJson("{}")
                .validFrom(now.minusHours(1)).publishReason("write routing fixture")
                .publishedBy(1L).publishedAt(now).createdAt(now).build();
        offerVersionMapper.insert(offerVersion);
        offer.setCurrentVersionId(offerVersion.getId());
        offer.setUpdatedAt(now);
        assertThat(offerMapper.updateById(offer)).isEqualTo(1);
    }

    private static RoutingPolicyPublicationService.CandidateDraft candidate(
            String supplierCode, int candidatePriority) {
        return new RoutingPolicyPublicationService.CandidateDraft(
                supplierCode, candidatePriority, "UNSUPPORTED", null,
                "UNSUPPORTED", null, "UNSUPPORTED", null);
    }

    /** 便捷封装数据库时钟，避免正文反复 getBean。 */
    private static final class SupplyChainDatabaseClockAdapter {
        private final com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock delegate;

        SupplyChainDatabaseClockAdapter(ConfigurableApplicationContext context) {
            this.delegate = context.getBean(
                    com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class);
        }

        LocalDateTime now() {
            return delegate.now();
        }
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
            ProductCenterTestConfiguration.class,
            RoutingPolicyPublicationService.class, SupplierRoutingApplicationService.class,
            RoutingRuleAdminQueryService.class, RoutingRuleAdminCommandService.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
