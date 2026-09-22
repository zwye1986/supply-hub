package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRuleView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
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

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_LAST_CANNOT_REMOVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 验证路由规则页补丁后继发布：候选顺序变更生效、路由选中随 priority 变化、删最后一条拒绝。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#10-测试门禁}。
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
    private static final String SKU_100 = "ROUTE_WRITE_100";
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
            SupplyChainDatabaseClock clock = context.getBean(SupplyChainDatabaseClock.class);
            BuyerMapper buyerMapper = context.getBean(BuyerMapper.class);
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
            seedBuyerAndSkus(buyerService, catalogService, seedNow);
            BuyerDO buyer = buyerMapper.selectByBuyerCode(BUYER_CODE);
            PlatformSkuDO sku200 = skuMapper.selectByCode(SKU_200);
            createOfferFixture(SUPPLIER_A, "奇那梦写测", 900L, sku200, seedNow,
                    supplierMapper, credentialMapper, connectorMapper, connectorVersionMapper,
                    inventoryMapper, offerMapper, offerVersionMapper);
            createOfferFixture(SUPPLIER_B, "后备写测", 100L, sku200, seedNow,
                    supplierMapper, credentialMapper, connectorMapper, connectorVersionMapper,
                    inventoryMapper, offerMapper, offerVersionMapper);

            RoutingPolicyPublicationService.DraftResult draft = publication.createDraft(
                    new RoutingPolicyPublicationService.CreateDraftCommand(
                            POLICY_CODE, POLICY_NAME, "写测双规则首版",
                            seedNow.minusHours(1), null,
                            List.of(
                                    ruleDraft(RULE_1, "写测规则一", SKU_100),
                                    ruleDraft(RULE_2, "写测规则二", SKU_200)),
                            1L));
            RoutingPolicyPublicationService.PublishResult published = publication.publish(
                    new RoutingPolicyPublicationService.PublishCommand(
                            draft.policyVersionId(), 0L, "写测种子发布",
                            1L, "trace-write-seed", null, null));
            assertThat(published.versionNo()).isEqualTo(1);
            Long firstVersionId = published.policyVersionId();

            RoutingRuleView before = queryService.get(RULE_2);
            assertThat(before.supplierCodes()).containsExactly(SUPPLIER_A, SUPPLIER_B);

            RoutingInput routeInput = new RoutingInput(
                    buyer.getId(), sku200.getId(), "DIRECT_RECHARGE", 1L, clock.now(), Map.of());
            SupplierRoutingApplicationService.RoutingSnapshot beforeRoute = routing.route(routeInput);
            assertThat(beforeRoute.decision().selectedCandidate().supplierCode()).isEqualTo(SUPPLIER_A);
            assertThat(beforeRoute.decision().reasonCode()).isEqualTo("SELECTED_BY_STABLE_PRIORITY");

            LocalDateTime beforeUpdate = clock.now();
            NormalizedRulePatch swapPatch = new NormalizedRulePatch(
                    "写测规则二", BUYER_CODE, SKU_200, "DIRECT_RECHARGE",
                    RoutingRuleRouteMode.AUTO, List.of(SUPPLIER_B, SUPPLIER_A), RULE_2);
            RoutingRuleView updatedView = commandService.update(RULE_2, swapPatch, OPERATOR_ID);
            assertThat(updatedView.ruleCode()).isEqualTo(RULE_2);
            assertThat(updatedView.supplierCodes()).containsExactly(SUPPLIER_B, SUPPLIER_A);

            LocalDateTime afterUpdateAt = clock.now();
            SupplierRoutingPolicyVersionDO closed = versionMapper.selectById(firstVersionId);
            assertThat(closed.getValidTo()).isNotNull();
            assertThat(closed.getValidTo()).isAfterOrEqualTo(beforeUpdate.withNano(0));
            assertThat(closed.getValidTo()).isBeforeOrEqualTo(afterUpdateAt);

            List<SupplierRoutingPolicyVersionDO> effective =
                    versionMapper.selectEffective(draft.policyId(), afterUpdateAt);
            assertThat(effective).hasSize(1);
            assertThat(effective.getFirst().getId()).isNotEqualTo(firstVersionId);
            assertThat(effective.getFirst().getVersionNo()).isEqualTo(2);
            assertThat(effective.getFirst().getValidFrom()).isEqualTo(closed.getValidTo());

            SupplierRoutingApplicationService.RoutingSnapshot afterRoute = routing.route(
                    new RoutingInput(buyer.getId(), sku200.getId(), "DIRECT_RECHARGE",
                            1L, afterUpdateAt, Map.of()));
            assertThat(afterRoute.policyVersionId()).isEqualTo(effective.getFirst().getId());
            assertThat(afterRoute.decision().selected()).isTrue();
            assertThat(afterRoute.decision().selectedCandidate())
                    .extracting(RoutingCandidate::supplierCode)
                    .isEqualTo(SUPPLIER_B);
            assertThat(afterRoute.decision().orderedCandidates())
                    .extracting(RoutingCandidate::supplierCode)
                    .containsExactly(SUPPLIER_B, SUPPLIER_A);
            assertThat(afterRoute.decision().reasonCode()).isEqualTo("SELECTED_BY_STABLE_PRIORITY");

            commandService.delete(RULE_1, OPERATOR_ID);
            assertThatThrownBy(() -> queryService.get(RULE_1))
                    .isInstanceOf(ServiceException.class);
            assertThat(queryService.get(RULE_2).ruleCode()).isEqualTo(RULE_2);

            assertThatThrownBy(() -> commandService.delete(RULE_2, OPERATOR_ID))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(ROUTING_RULE_LAST_CANNOT_REMOVE.getCode());
        }
    }

    private static void seedBuyerAndSkus(
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
                SKU_100, "WRITE_TELCO", "写测话费直充 100 元",
                "DIRECT_RECHARGE", 10_000L, "CNY", 1L));
        catalog.changeSkuStatus(SKU_100, CatalogEntityStatus.ACTIVE, 1L);
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

    private static RoutingPolicyPublicationService.RuleDraft ruleDraft(
            String ruleCode, String ruleName, String skuCode) {
        return new RoutingPolicyPublicationService.RuleDraft(
                ruleCode, ruleName, BUYER_CODE, skuCode, "DIRECT_RECHARGE", 10,
                List.of(candidate(SUPPLIER_A, 0), candidate(SUPPLIER_B, 1)));
    }

    private static RoutingPolicyPublicationService.CandidateDraft candidate(
            String supplierCode, int priority) {
        return new RoutingPolicyPublicationService.CandidateDraft(
                supplierCode, priority, "UNSUPPORTED", null,
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
