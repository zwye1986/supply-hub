package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.routing.domain.SupplierRoutingEngine;
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
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierRoutingApplicationServiceIntegrationTest {

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
    void publishesExactPolicyAndRoutesByStablePriorityWithoutDynamicScoring() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BuyerCommandService buyerService = context.getBean(BuyerCommandService.class);
            ProductCatalogCommandService catalogService = context.getBean(ProductCatalogCommandService.class);
            RoutingPolicyPublicationService publication =
                    context.getBean(RoutingPolicyPublicationService.class);
            SupplierRoutingApplicationService routing =
                    context.getBean(SupplierRoutingApplicationService.class);
            BuyerMapper buyerMapper = context.getBean(BuyerMapper.class);
            PlatformSkuMapper skuMapper = context.getBean(PlatformSkuMapper.class);
            SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);
            CredentialSlotMapper credentialMapper = context.getBean(CredentialSlotMapper.class);
            SupplierConnectorMapper connectorMapper = context.getBean(SupplierConnectorMapper.class);
            SupplierConnectorVersionMapper connectorVersionMapper =
                    context.getBean(SupplierConnectorVersionMapper.class);
            InventorySourceMapper inventoryMapper = context.getBean(InventorySourceMapper.class);
            SupplierOfferMapper offerMapper = context.getBean(SupplierOfferMapper.class);
            SupplierOfferVersionMapper offerVersionMapper = context.getBean(SupplierOfferVersionMapper.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(
                    "supplyChainDataSource", javax.sql.DataSource.class));

            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
            createBuyerAndSku(buyerService, catalogService, now);
            BuyerDO buyer = buyerMapper.selectByBuyerCode("WUSHENG");
            PlatformSkuDO sku = skuMapper.selectByCode("ROUTE_TELCO_100");
            OfferFixture qnm = createOfferFixture("QINAMENG", "奇那梦", 900L, sku, now,
                    supplierMapper, credentialMapper, connectorMapper, connectorVersionMapper,
                    inventoryMapper, offerMapper, offerVersionMapper);
            OfferFixture zeta = createOfferFixture("ZETA", "后备供应商", 100L, sku, now,
                    supplierMapper, credentialMapper, connectorMapper, connectorVersionMapper,
                    inventoryMapper, offerMapper, offerVersionMapper);

            assertThatThrownBy(() -> publication.createDraft(duplicateRuleDraft(now)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Overlapping exact routing rule");
            assertThatThrownBy(() -> publication.createDraft(emptyCandidateDraft(now)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no candidates");

            RoutingPolicyPublicationService.DraftResult draft =
                    publication.createDraft(validDraft(now));
            RoutingPolicyPublicationService.PublishResult published = publication.publish(
                    new RoutingPolicyPublicationService.PublishCommand(
                            draft.policyVersionId(), 0L, "一期确定性标准商品路由",
                            1L, "trace-routing-publish", null, null));
            assertThat(published.versionNo()).isEqualTo(1);
            assertThat(published.configDigestHex()).hasSize(64);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event "
                    + "WHERE event_type = 'ROUTING_POLICY_PUBLISHED'", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_standard_product_routing_rule "
                    + "WHERE buyer_id IS NULL OR platform_sku_id IS NULL OR fulfillment_type IS NULL",
                    Long.class)).isZero();

            RoutingInput input = new RoutingInput(buyer.getId(), sku.getId(),
                    "DIRECT_RECHARGE", 1L, now, Map.of());
            SupplierRoutingApplicationService.RoutingSnapshot first = routing.route(input);
            assertThat(first.policyVersionId()).isEqualTo(draft.policyVersionId());
            assertThat(first.configDigest()).hasSize(32);
            assertThat(first.decision().selected()).isTrue();
            assertThat(first.decision().selectedCandidate())
                    .extracting(RoutingCandidate::supplierCode,
                            RoutingCandidate::offerVersionId,
                            RoutingCandidate::supplierSettlementUnitPrice,
                            RoutingCandidate::availabilityClass)
                    .containsExactly("QINAMENG", qnm.offerVersionId(), 900L, "UNKNOWN");
            assertThat(first.decision().orderedCandidates())
                    .extracting(RoutingCandidate::supplierCode)
                    .containsExactly("QINAMENG", "ZETA");

            List<RoutingCandidate> shuffled = new ArrayList<>(first.decision().orderedCandidates());
            SupplierRoutingEngine engine = new SupplierRoutingEngine();
            Random random = new Random(20260812L);
            for (int attempt = 0; attempt < 100; attempt++) {
                Collections.shuffle(shuffled, random);
                assertThat(engine.decide(input, shuffled).selectedCandidate().supplierCode())
                        .isEqualTo("QINAMENG");
            }

            RoutingInput.Fact qnmHealthError = new RoutingInput.Fact(
                    RoutingInput.FactStatus.ERROR, null,
                    now.minusSeconds(10), now.plusSeconds(50));
            RoutingInput degraded = new RoutingInput(buyer.getId(), sku.getId(),
                    "DIRECT_RECHARGE", 1L, now,
                    Map.of("QINAMENG", new RoutingInput.CandidateFacts(null, null, qnmHealthError)));
            SupplierRoutingApplicationService.RoutingSnapshot fallback = routing.route(degraded);
            assertThat(fallback.decision().selectedCandidate().supplierCode()).isEqualTo("ZETA");
            assertThat(fallback.decision().orderedCandidates().getFirst().rejectionReasons())
                    .containsExactly("HEALTH_ERROR");
            assertThat(fallback.decision().orderedCandidates().get(1).supplierSettlementUnitPrice())
                    .isEqualTo(zeta.price());

            Long replacementVersionId = replaceCurrentOfferVersion(
                    qnm.offerId(), qnm.connectorVersionId(), qnm.inventorySourceId(),
                    850L, now, offerMapper, offerVersionMapper);
            SupplierRoutingApplicationService.RoutingSnapshot afterReplacement = routing.route(input);
            assertThat(afterReplacement.decision().selectedCandidate().offerVersionId())
                    .isEqualTo(replacementVersionId);
            assertThat(first.decision().selectedCandidate().offerVersionId())
                    .isEqualTo(qnm.offerVersionId());

            RoutingPolicyPublicationService.DraftResult overlap = publication.createDraft(
                    new RoutingPolicyPublicationService.CreateDraftCommand(
                            "CARD_PRIMARY", "卡券与直充主路由", "重叠版本",
                            now.minusMinutes(30), now.plusDays(2), validRules(), 1L));
            assertThatThrownBy(() -> publication.publish(
                    new RoutingPolicyPublicationService.PublishCommand(
                            overlap.policyVersionId(), 0L, "非法重叠版本",
                            1L, "trace-overlap", null, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("overlaps a published version");

            RoutingPolicyPublicationService.DraftResult scheduled = publication.createDraft(
                    new RoutingPolicyPublicationService.CreateDraftCommand(
                            "CARD_PRIMARY", "卡券与直充主路由", "下一生效版本",
                            now.plusDays(1), now.plusDays(2), validRules(), 1L));
            publication.publish(new RoutingPolicyPublicationService.PublishCommand(
                    scheduled.policyVersionId(), 0L, "按业务时间连续生效",
                    1L, "trace-scheduled", null, null));
            RoutingInput futureInput = new RoutingInput(buyer.getId(), sku.getId(),
                    "DIRECT_RECHARGE", 1L, now.plusDays(1).plusMinutes(1), Map.of());
            assertThat(routing.route(futureInput).policyVersionId())
                    .isEqualTo(scheduled.policyVersionId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event "
                    + "WHERE event_type = 'ROUTING_POLICY_PUBLISHED'", Long.class)).isEqualTo(2L);
        }
    }

    private static void createBuyerAndSku(BuyerCommandService buyerService,
                                          ProductCatalogCommandService catalog,
                                          LocalDateTime now) {
        buyerService.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                "WUSHENG", "吾盛", "吾盛", null, null,
                "AIFULI_MALL", "tenant-routing", now.minusMinutes(1), 1L));
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "ROUTE_BRAND", "路由测试品牌", null, null, 0, 1L));
        catalog.changeBrandStatus("ROUTE_BRAND", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "ROUTE_DIRECT", "路由直充", null, 0, 1L));
        catalog.changeCategoryStatus("ROUTE_DIRECT", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "ROUTE_TELCO", "ROUTE_BRAND", "ROUTE_DIRECT",
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "路由话费直充", null, null, "中台受理后不可自动退款或撤销", 1L));
        catalog.changeSpuStatus("ROUTE_TELCO", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "ROUTE_TELCO_100", "ROUTE_TELCO", "路由话费直充 100 元",
                "DIRECT_RECHARGE", 10_000L, "CNY", 1L));
        catalog.changeSkuStatus("ROUTE_TELCO_100", CatalogEntityStatus.ACTIVE, 1L);
    }

    private static OfferFixture createOfferFixture(
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
                .slotNo("CRD_FIX_" + supplierCode).providerType("ENV")
                .secretRef("env:FIX_" + supplierCode).maskedHint("fixture-****")
                .activeSecretVersion("v1").status((short) 10).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        credentialMapper.insert(credential);
        SupplierConnectorDO connector = SupplierConnectorDO.builder()
                .connectorNo("CON_FIX_" + supplierCode).supplierId(supplier.getId())
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
                .sourceNo("INV_FIX_" + supplierCode).supplierId(supplier.getId())
                .platformSkuId(sku.getId()).sourceType("REMOTE_ON_DEMAND")
                .externalSourceCode("REMOTE_" + supplierCode).ownerType("SUPPLIER")
                .status(InventorySourceStatus.ACTIVE.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        inventoryMapper.insert(inventory);
        SupplierOfferDO offer = SupplierOfferDO.builder()
                .offerNo("OFR_FIX_" + supplierCode).offerCode("TELCO_100")
                .supplierId(supplier.getId()).platformSkuId(sku.getId())
                .status(OfferStatus.PUBLISHED.getCode()).version(0L)
                .createdBy(1L).createdAt(now).updatedBy(1L).updatedAt(now).build();
        offerMapper.insert(offer);
        SupplierOfferVersionDO offerVersion = offerVersion(offer.getId(), 1,
                connectorVersion.getId(), inventory.getId(), price, now);
        offerVersionMapper.insert(offerVersion);
        offer.setCurrentVersionId(offerVersion.getId());
        offer.setUpdatedAt(now);
        assertThat(offerMapper.updateById(offer)).isEqualTo(1);
        return new OfferFixture(offer.getId(), offerVersion.getId(), connectorVersion.getId(),
                inventory.getId(), price);
    }

    private static Long replaceCurrentOfferVersion(
            Long offerId, Long connectorVersionId, Long inventorySourceId, long price, LocalDateTime now,
            SupplierOfferMapper offerMapper, SupplierOfferVersionMapper offerVersionMapper) {
        SupplierOfferVersionDO replacement = offerVersion(
                offerId, 2, connectorVersionId, inventorySourceId, price, now.minusMinutes(1));
        offerVersionMapper.insert(replacement);
        SupplierOfferDO offer = offerMapper.selectById(offerId);
        offer.setCurrentVersionId(replacement.getId());
        offer.setUpdatedAt(now);
        assertThat(offerMapper.updateById(offer)).isEqualTo(1);
        return replacement.getId();
    }

    private static SupplierOfferVersionDO offerVersion(
            Long offerId, int versionNo, Long connectorVersionId,
            Long inventorySourceId, long price, LocalDateTime validFrom) {
        return SupplierOfferVersionDO.builder()
                .offerId(offerId).versionNo(versionNo).externalSkuCode("EXT-" + offerId)
                .supplierSettlementUnitPrice(price).currency("CNY")
                .connectorVersionId(connectorVersionId).inventorySourceId(inventorySourceId)
                .minQuantity(1L).maxQuantity(1L).quantityStep(1L)
                .primaryCapabilityCode("CREATE_RECHARGE").completionMode("QUERY_OR_CALLBACK")
                .createIdempotencyMode("DEDUP_ONLY").unknownResultPolicy("QUERY_ORDER")
                .partialDeliveryMode("FORBIDDEN").resultMultiplicity("NONE")
                .fulfillmentConfigSchemaVersion(1).fulfillmentConfigJson("{}")
                .validFrom(validFrom.minusHours(1)).publishReason("routing fixture")
                .publishedBy(1L).publishedAt(validFrom).createdAt(validFrom).build();
    }

    private static RoutingPolicyPublicationService.CreateDraftCommand validDraft(LocalDateTime now) {
        return new RoutingPolicyPublicationService.CreateDraftCommand(
                "CARD_PRIMARY", "卡券与直充主路由", "一期首版",
                now.minusHours(1), now.plusDays(1), validRules(), 1L);
    }

    private static List<RoutingPolicyPublicationService.RuleDraft> validRules() {
        return List.of(new RoutingPolicyPublicationService.RuleDraft(
                "WS_TELCO", "吾盛话费路由", "WUSHENG", "ROUTE_TELCO_100",
                "DIRECT_RECHARGE", 10,
                List.of(optionalHealthCandidate("ZETA"), optionalHealthCandidate("QINAMENG"))));
    }

    private static RoutingPolicyPublicationService.CandidateDraft optionalHealthCandidate(String supplierCode) {
        return new RoutingPolicyPublicationService.CandidateDraft(
                supplierCode, 10, "UNSUPPORTED", null,
                "UNSUPPORTED", null, "OPTIONAL", 60);
    }

    private static RoutingPolicyPublicationService.CreateDraftCommand duplicateRuleDraft(LocalDateTime now) {
        RoutingPolicyPublicationService.RuleDraft rule = validRules().getFirst();
        RoutingPolicyPublicationService.RuleDraft duplicate =
                new RoutingPolicyPublicationService.RuleDraft(
                        "WS_TELCO_DUP", "重复精确规则", rule.buyerCode(), rule.platformSkuCode(),
                        rule.fulfillmentType(), 20, rule.candidates());
        return new RoutingPolicyPublicationService.CreateDraftCommand(
                "INVALID_DUP", "非法重复路由", "重复规则",
                now.minusHours(1), now.plusDays(1), List.of(rule, duplicate), 1L);
    }

    private static RoutingPolicyPublicationService.CreateDraftCommand emptyCandidateDraft(LocalDateTime now) {
        RoutingPolicyPublicationService.RuleDraft empty =
                new RoutingPolicyPublicationService.RuleDraft(
                        "EMPTY", "空候选", "WUSHENG", "ROUTE_TELCO_100",
                        "DIRECT_RECHARGE", 10, List.of());
        return new RoutingPolicyPublicationService.CreateDraftCommand(
                "INVALID_EMPTY", "非法空候选路由", "空候选",
                now.minusHours(1), now.plusDays(1), List.of(empty), 1L);
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
            RoutingPolicyPublicationService.class, SupplierRoutingApplicationService.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    private record OfferFixture(Long offerId, Long offerVersionId,
                                Long connectorVersionId, Long inventorySourceId, long price) {
    }
}
