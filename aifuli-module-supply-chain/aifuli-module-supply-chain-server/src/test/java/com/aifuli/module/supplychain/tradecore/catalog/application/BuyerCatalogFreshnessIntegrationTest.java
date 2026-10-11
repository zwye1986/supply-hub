package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogItemRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogPageReqDTO;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.BuyerProductPricingInitializationEvent;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PlatformDefaultPricingPolicy;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PlatformSkuPricingInitializationEvent;
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
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AUTO投影陈旧读侧：期限为空／不超过库时钟、价格来源不匹配→UNKNOWN，availableOnly同规则排除，MANUAL保持旧准入。
 * 真实MySQL／Liquibase／读模型SQL验证。设计见 §6.4「投影陈旧安全」。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyerCatalogFreshnessIntegrationTest {

    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;

    private BuyerSelectionCatalogRefreshService refreshService;
    private BuyerCatalogQueryService queryService;
    private BuyerListingMapper listingMapper;
    private BuyerListingAvailabilityMapper availabilityMapper;
    private GenericBatchPricingService pricingService;

    private long sequence;
    private BuyerDO buyer;
    private String tenantId;
    private BuyerProductDO product;
    private BuyerListingDO listing;

    @BeforeAll
    void start() {
        mysql.start();
        context = new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
"aifuli.supply-chain.connector.allowed-adapter-types", "QNM_GAME_ORDER_V1",
                        "aifuli.supply-chain.datasource.url", mysql.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username", mysql.getUsername(),
                        "aifuli.supply-chain.datasource.password", mysql.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver",
                        "aifuli.supply-chain.catalog.system-operator-id", "9001"))
                .run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        refreshService = context.getBean(BuyerSelectionCatalogRefreshService.class);
        queryService = context.getBean(BuyerCatalogQueryService.class);
        listingMapper = context.getBean(BuyerListingMapper.class);
        availabilityMapper = context.getBean(BuyerListingAvailabilityMapper.class);
        pricingService = context.getBean(GenericBatchPricingService.class);
    }

    @AfterAll
    void stop() {
        if (context != null) context.close();
        mysql.stop();
    }

    @Test
    void freshAutoProjectionIsAvailableWithDeadlineCappedAtMaxEvaluationAge() {
        buildFixture();
        BuyerSelectionCatalogRefreshResult result = refreshService.refresh(listing.getId(),
                BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 7L);
        assertThat(result.availabilityStatus()).isEqualTo(ListingAvailability.AVAILABLE);

        BuyerListingAvailabilityDO projection = availabilityMapper.selectByListingId(listing.getId());
        assertThat(projection.getValidUntil()).isEqualTo(projection.getEvaluatedAt().plusSeconds(30));

        assertThat(page(false).getList()).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.AVAILABLE.name());
        assertThat(page(true).getTotal()).isEqualTo(1L);
    }

    @Test
    void expiredOrMissingAutoDeadlineReturnsUnknownAndExcludedFromAvailableOnly() {
        buildFixture();
        publishAuto();
        jdbc.update("UPDATE sc_buyer_listing_availability SET valid_until=evaluated_at WHERE listing_id=?",
                listing.getId());
        assertThat(page(false).getList()).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNKNOWN.name());
        assertThat(page(true).getTotal()).isZero();

        jdbc.update("UPDATE sc_buyer_listing_availability SET valid_until=NULL WHERE listing_id=?", listing.getId());
        assertThat(page(false).getList()).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNKNOWN.name());
        assertThat(page(true).getTotal()).isZero();
    }

    @Test
    void stalePriceSourceReturnsUnknownAndExcludedFromAvailableOnly() {
        buildFixture();
        publishAuto();
        // 平台定价重算前移买家定价当前版本：商业版本来源仍指向旧版本→来源不匹配。
        pricingService.savePlatform(List.of(skuCode()), PlatformDefaultPricingPolicy.create(), 1L);

        assertThat(page(false).getList()).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNKNOWN.name());
        assertThat(page(true).getTotal()).isZero();
    }

    @Test
    void manualListingKeepsLegacyReadBehaviorRegardlessOfDeadline() {
        buildFixture();
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        String manualSku = "SKU_%03d_200".formatted(sequence);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(manualSku, spuCode(),
                "手工SKU" + sequence, "DIRECT_RECHARGE", 1000L, "CNY", 1L));
        catalog.changeSkuStatus(manualSku, CatalogEntityStatus.ACTIVE, 1L);
        String manualNo = catalog.createBuyerListing(
                new ProductCatalogCommandService.CreateBuyerListingCommand(buyerCode(),
                        manualSku, "MANUAL-ALIAS-" + sequence, 1L));
        context.getBean(BuyerListingPublicationService.class).publish(
                new BuyerListingPublicationService.PublishListingCommand(manualNo, 0L, 500L,
                        "手工目录" + sequence, LocalDateTime.now().minusMinutes(1), null, "人工发布", 7L,
                        "trace-" + sequence, null, null));

        assertThat(page(false).getList()).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNKNOWN.name());
        jdbc.update("UPDATE sc_buyer_listing_availability SET valid_until=NULL "
                + "WHERE listing_id=(SELECT id FROM sc_buyer_listing WHERE listing_no=?)", manualNo);
        assertThat(page(false).getList()).extracting(BuyerCatalogItemRespDTO::getAvailabilityStatus)
                .containsExactly(ListingAvailability.UNKNOWN.name());
    }

    // ------------------------------------------------------------------ 夹具

    private PageResult<BuyerCatalogItemRespDTO> page(boolean availableOnly) {
        BuyerCatalogPageReqDTO request = new BuyerCatalogPageReqDTO();
        request.setSourceSystem("AIFULI_MALL");
        request.setExternalTenantId(tenantId);
        request.setPageNo(1);
        request.setPageSize(20);
        request.setAvailableOnly(availableOnly);
        return queryService.pageCatalog(request);
    }

    private void publishAuto() {
        refreshService.refresh(listing.getId(), BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 7L);
    }

    private String buyerCode() {
        return jdbc.queryForObject("SELECT buyer_code FROM sc_buyer WHERE id=?", String.class, buyer.getId());
    }

    private String skuCode() {
        return jdbc.queryForObject("SELECT sku_code FROM sc_platform_sku WHERE id=?", String.class, listing.getPlatformSkuId());
    }

    private String spuCode() {
        return jdbc.queryForObject("""
                SELECT spu.spu_code FROM sc_platform_sku sku
                JOIN sc_platform_spu spu ON spu.id = sku.spu_id WHERE sku.id=?
                """, String.class, listing.getPlatformSkuId());
    }

    private void buildFixture() {
        for (String table : List.of("sc_std_routing_rule_scope", "sc_std_routing_candidate", "sc_std_routing_rule",
                "sc_supplier_routing_policy_version", "sc_supplier_routing_policy")) {
            jdbc.update("DELETE FROM " + table);
        }
        LocalDateTime now = context.getBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class).now();
        String suffix = Long.toString(++sequence);
        tenantId = "fresh-" + suffix;
        String buyerCode = context.getBean(BuyerCommandService.class).createBuyer(
                new BuyerCommandService.CreateBuyerCommand(
                        null, "新鲜买家" + suffix, "新鲜买家" + suffix, null, null, "AIFULI_MALL",
                        tenantId, now.minusMinutes(1), 1L));
        buyer = context.getBean(BuyerMapper.class).selectByBuyerCode(buyerCode);
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        String brand = "BRD_%03d".formatted(sequence), category = "CAT_%03d".formatted(sequence);
        String spuCode = "SPU_%03d".formatted(sequence);
        String code = "SKU_%03d_100".formatted(sequence);
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(brand, brand, null, null, 0, 1L));
        catalog.changeBrandStatus(brand, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(category, category, null, 0, 1L));
        catalog.changeCategoryStatus(category, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(spuCode, brand, category,
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", spuCode, null, null, "不可自动撤销", 1L));
        catalog.changeSpuStatus(spuCode, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(code, spuCode, "新鲜SKU" + suffix,
                "DIRECT_RECHARGE", 1000L, "CNY", 1L));
        catalog.changeSkuStatus(code, CatalogEntityStatus.ACTIVE, 1L);
        PlatformSkuDO sku = context.getBean(PlatformSkuMapper.class).selectByCode(code);
        jdbc.update("UPDATE sc_platform_sku SET market_price=1000, suggested_sale_price=1000 WHERE id=?", sku.getId());
        product = BuyerProductDO.builder().buyerProductCode("EP" + suffix).buyerId(buyer.getId())
                .platformSkuId(sku.getId()).status((short) 10).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(BuyerProductMapper.class).insert(product);
        listing = BuyerListingDO.builder().listingNo("LST" + suffix).buyerId(buyer.getId())
                .platformSkuId(sku.getId()).buyerSkuCode(product.getBuyerProductCode())
                .managementMode("AUTO_SELECTION").buyerProductId(product.getId())
                .status(BuyerListingStatus.DRAFT.getCode()).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        listingMapper.insert(listing);
        availabilityMapper.insert(BuyerListingAvailabilityDO.builder().listingId(listing.getId())
                .availability(ListingAvailability.UNKNOWN.getCode()).reasonCode("NOT_EVALUATED")
                .basisVersion(0L).version(0L).evaluatedAt(now).updatedAt(now).build());
        SupplierDO supplier = supply("SP" + suffix);
        pricingService.initializePlatformDefaults(new PlatformSkuPricingInitializationEvent(List.of(code), 1L));
        pricingService.initializeBuyerProduct(new BuyerProductPricingInitializationEvent(product.getId(), 1L));
        BuyerProjectDO project = project("PA" + suffix);
        projectListing(project, now);
        publishRules(List.of(new RoutingPolicyPublicationService.RuleDraft("RA" + suffix, "RA" + suffix,
                "FIXED", supplier.getSupplierCode(), "ALL", List.of(), "SPECIFIED",
                List.of(project.getProjectCode()), "SPECIFIED", List.of("EQUITY_PRODUCT"),
                "ALL", List.of(), null, "STABLE", "PRICE", "COMPOSITE", 10, (short) 10, false, List.of())));
    }

    private BuyerProjectDO project(String code) {
        LocalDateTime now = LocalDateTime.now();
        BuyerProjectDO project = BuyerProjectDO.builder().projectCode(code + sequence).projectName(code)
                .buyerId(buyer.getId()).catalogMode("MANAGED").settlementPartyType("DISTRIBUTOR")
                .status((short) 10).version(0L).createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(BuyerProjectMapper.class).insert(project);
        return project;
    }

    private void projectListing(BuyerProjectDO project, LocalDateTime now) {
        ProjectListingDO listing = ProjectListingDO.builder().listingCode("PL" + project.getProjectCode())
                .projectId(project.getId()).buyerProductId(product.getId()).status((short) 10).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(ProjectListingMapper.class).insert(listing);
        ProjectListingVersionDO version = ProjectListingVersionDO.builder().listingId(listing.getId())
                .versionNo(1).buyerSettlementUnitPrice(100L).settlementPriceLevel("L2")
                .priceSource("INHERITED").l3AdjustMode("FOLLOW_L2").currency("CNY")
                .publishedBy(1L).publishedAt(now).createdAt(now).build();
        context.getBean(ProjectListingVersionMapper.class).insert(version);
        jdbc.update("UPDATE sc_project_listing SET current_version_id=? WHERE id=?", version.getId(), listing.getId());
    }

    private SupplierDO supply(String code) {
        LocalDateTime now = LocalDateTime.now();
        String unique = code + sequence;
        SupplierDO created = SupplierDO.builder().supplierCode(unique).supplierName(unique).shortName(unique)
                .supplierType("STANDARD_SUPPLIER").status((short) 10).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierMapper.class).insert(created);
        CredentialSlotDO credential = CredentialSlotDO.builder().slotNo("CR" + unique).providerType("ENV")
                .secretRef("env:FIXTURE").maskedHint("fixture").activeSecretVersion("v1")
                .status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(CredentialSlotMapper.class).insert(credential);
        SupplierConnectorDO connector = SupplierConnectorDO.builder().connectorNo("CN" + unique)
                .supplierId(created.getId()).connectorCode("PRIMARY").adapterType("QNM_GAME_ORDER_V1")
                .status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierConnectorMapper.class).insert(connector);
        SupplierConnectorVersionDO connectorVersion = SupplierConnectorVersionDO.builder().connectorId(connector.getId())
                .versionNo(1).baseUrl("https://fixture.invalid").merchantAccount("fixture")
                .credentialSlotId(credential.getId()).protocolVersion("V1").callbackPath("/fixture")
                .connectTimeoutMs(1000).readTimeoutMs(3000).rateLimit(10).concurrencyLimit(2)
                .adapterConfigSchemaVersion(1).adapterConfigJson("{}").validationStatus((short) 10)
                .validatedAt(now).validationExpiresAt(now.plusDays(1)).publishedAt(now).configDigest(new byte[32])
                .status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierConnectorVersionMapper.class).insert(connectorVersion);
        // 共享结构有效性守卫要求主能力及执行画像所需查询/回调能力启用（生命周期设计 §3.2）。
        SupplierConnectorCapabilityMapper capabilityMapper = context.getBean(SupplierConnectorCapabilityMapper.class);
        for (String capabilityCode : List.of("CREATE_RECHARGE", "QUERY_ORDER", "RECEIVE_CALLBACK")) {
            capabilityMapper.insert(SupplierConnectorCapabilityDO.builder()
                    .connectorVersionId(connectorVersion.getId()).capabilityCode(capabilityCode)
                    .enabled((short) 1).metadataVersion(1).createdAt(now).build());
        }
        jdbc.update("UPDATE sc_supplier_connector SET current_version_id=? WHERE id=?",
                connectorVersion.getId(), connector.getId());
        InventorySourceDO inventory = InventorySourceDO.builder().sourceNo("IN" + unique).supplierId(created.getId())
                .platformSkuId(listing.getPlatformSkuId()).sourceType("REMOTE_ON_DEMAND").externalSourceCode(unique)
                .ownerType("SUPPLIER").status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(InventorySourceMapper.class).insert(inventory);
        SupplierOfferDO offer = SupplierOfferDO.builder().offerNo("OF" + unique).offerCode(unique)
                .supplierId(created.getId()).platformSkuId(listing.getPlatformSkuId()).stabilityScore(90).timelinessScore(90)
                .status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierOfferMapper.class).insert(offer);
        SupplierOfferVersionDO version = SupplierOfferVersionDO.builder().offerId(offer.getId()).versionNo(1)
                .externalSkuCode(unique).supplierSettlementUnitPrice(100L).currency("CNY")
                .connectorVersionId(connectorVersion.getId()).inventorySourceId(inventory.getId())
                .minQuantity(1L).maxQuantity(1L).quantityStep(1L).primaryCapabilityCode("CREATE_RECHARGE")
                .completionMode("QUERY_OR_CALLBACK").createIdempotencyMode("DEDUP_ONLY").unknownResultPolicy("QUERY_ORDER")
                .partialDeliveryMode("FORBIDDEN").resultMultiplicity("NONE").fulfillmentConfigSchemaVersion(1)
                .fulfillmentConfigJson("{}").validFrom(now.minusDays(1)).validTo(now.plusDays(1))
                .publishReason("fixture").publishedBy(1L).publishedAt(now).createdAt(now).build();
        context.getBean(SupplierOfferVersionMapper.class).insert(version);
        jdbc.update("UPDATE sc_supplier_offer SET current_version_id=? WHERE id=?", version.getId(), offer.getId());
        return created;
    }

    private void publishRules(List<RoutingPolicyPublicationService.RuleDraft> rules) {
        RoutingPolicyPublicationService publicationService = context.getBean(RoutingPolicyPublicationService.class);
        var draft = publicationService.createDraft(new RoutingPolicyPublicationService.CreateDraftCommand(
                "FRESH" + sequence, "新鲜策略", "V1", LocalDateTime.now().minusMinutes(1), null, rules, 1L, 40, 30, 30));
        publicationService.publish(new RoutingPolicyPublicationService.PublishCommand(draft.policyVersionId(), 0L,
                "新鲜夹具", 1L, "fixture", null, null));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxEventRepository.class, BuyerCommandService.class, BuyerResolver.class,
            ProductCatalogCommandService.class, ProductCenterTestConfiguration.class,
            RoutingPolicyPublicationService.class, SupplierRoutingApplicationService.class,
            GenericBatchPricingService.class, BuyerListingPublicationService.class,
            BuyerCatalogQueryService.class,
            com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties.class})
    @ComponentScan(basePackages = "com.aifuli.module.supplychain.tradecore.catalog.application",
            useDefaultFilters = false, includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = ".*(CurrentSupplyEligibilityReader|BuyerSelectionCatalogRefreshService"
                            + "|BuyerSelectionCatalogRefreshTransactionalWorker)"))
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        TaxRateDictionaryAdapter taxRateDictionaryAdapter() {
            TaxRateDictionaryAdapter adapter = mock(TaxRateDictionaryAdapter.class);
            when(adapter.requireEnabled(any(), anyString())).thenAnswer(invocation ->
                    new TaxMetadata(invocation.getArgument(0), invocation.getArgument(1), "不征税"));
            return adapter;
        }
    }
}
