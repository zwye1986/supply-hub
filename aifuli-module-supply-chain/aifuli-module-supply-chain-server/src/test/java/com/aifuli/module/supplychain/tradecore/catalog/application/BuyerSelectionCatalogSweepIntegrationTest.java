package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
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
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerCatalogRefreshOutcome;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.BuyerProductPricingInitializationEvent;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
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
 * 有界公平扫描：真实MySQL验证无用户操作自动发布、供给失效/恢复、人工暂停不恢复、
 * 损坏目录不阻断其余、上界冻结与新增行下次遍历覆盖。设计 §6.1～§6.3。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyerSelectionCatalogSweepIntegrationTest {

    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;

    private BuyerSelectionCatalogSweepService sweepService;
    private BuyerSelectionCatalogRefreshService refreshService;
    private BuyerListingPublicationService publication;
    private BuyerListingMapper listingMapper;
    private BuyerListingAvailabilityMapper availabilityMapper;

    private long sequence;
    private BuyerDO buyer;
    private BuyerProductDO product;
    private BuyerListingDO listing;
    private SupplierDO supplier;
    private BuyerListingDO brokenListing;

    @BeforeAll
    void start() {
        mysql.start();
        context = new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", mysql.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username", mysql.getUsername(),
                        "aifuli.supply-chain.datasource.password", mysql.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"))
                // 命令行参数优先级高于classpath application.yaml（其中catalog默认键会覆盖defaultProperties）。
                .run("--aifuli.supply-chain.catalog.system-operator-id=9001",
                        "--aifuli.supply-chain.catalog.sweep-batch-size=2");
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        sweepService = context.getBean(BuyerSelectionCatalogSweepService.class);
        refreshService = context.getBean(BuyerSelectionCatalogRefreshService.class);
        publication = context.getBean(BuyerListingPublicationService.class);
        listingMapper = context.getBean(BuyerListingMapper.class);
        availabilityMapper = context.getBean(BuyerListingAvailabilityMapper.class);
    }

    @AfterAll
    void stop() {
        if (context != null) context.close();
        mysql.stop();
    }

    @Test
    void sweepPublishesDraftWhenPricingRestoredWithoutUserAction() {
        buildFixture();
        jdbc.update("UPDATE sc_buyer_sku_pricing SET pricing_status='UNPRICED' WHERE buyer_product_id=?",
                product.getId());

        BuyerSelectionCatalogSweepService.SweepBatch waiting = sweepOnce();
        assertThat(waiting.skipped()).isGreaterThanOrEqualTo(1);
        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.DRAFT.getCode());

        context.getBean(GenericBatchPricingService.class).initializeBuyerProduct(
                new BuyerProductPricingInitializationEvent(product.getId(), 1L));
        BuyerSelectionCatalogSweepService.SweepBatch published = sweepOnce();
        assertThat(published.published()).isGreaterThanOrEqualTo(1);
        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.PUBLISHED.getCode());
        assertThat(availabilityMapper.selectByListingId(listing.getId()).getAvailability())
                .isEqualTo(ListingAvailability.AVAILABLE.getCode());
    }

    @Test
    void sweepReflectsSupplyOutageAndRecovery() {
        buildFixture();
        refreshService.refresh(listing.getId(), BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 7L);
        jdbc.update("UPDATE sc_supplier SET status=20 WHERE id=?", supplier.getId());

        sweepOnce();
        assertThat(availabilityMapper.selectByListingId(listing.getId()).getAvailability())
                .isEqualTo(ListingAvailability.UNAVAILABLE.getCode());

        jdbc.update("UPDATE sc_supplier SET status=10 WHERE id=?", supplier.getId());
        sweepOnce();
        assertThat(availabilityMapper.selectByListingId(listing.getId()).getAvailability())
                .isEqualTo(ListingAvailability.AVAILABLE.getCode());
    }

    @Test
    void suspendedListingStaysPausedAcrossSweeps() {
        buildFixture();
        refreshService.refresh(listing.getId(), BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 7L);
        changeStatus(listing.getId(), BuyerListingStatus.SUSPENDED);

        BuyerSelectionCatalogSweepService.SweepBatch batch = sweepOnce();

        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.SUSPENDED.getCode());
        assertThat(batch.published()).isZero();
    }

    @Test
    void brokenListingDoesNotBlockRestOfBatch() {
        buildFixture();
        buildBrokenListing();
        // 两个候选：正常DRAFT可发布，损坏目录技术失败。batch=2 一批内尝试两者。
        BuyerSelectionCatalogSweepService.SweepBatch batch = sweepOnce();

        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.PUBLISHED.getCode());
        assertThat(batch.attempted()).as("同批必须尝试正常与损坏两条").isEqualTo(2);
        assertThat(batch.failed()).isGreaterThanOrEqualTo(1);
        assertThat(batch.published()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void upperIdFreezeCoversNewHighIdsOnlyInNextTraversal() {
        buildFixture();
        refreshService.refresh(listing.getId(), BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 7L);
        // 第一遍遍历：只扫已存在行；中途新增高ID不进入本遍历。
        BuyerSelectionCatalogSweepService.SweepBatch first = sweepOnce();
        assertThat(first.attempted()).isGreaterThanOrEqualTo(1);

        // 新遍历覆盖新增行。
        jdbc.update("UPDATE sc_supplier SET status=20 WHERE id=?", supplier.getId());
        BuyerSelectionCatalogSweepService.SweepBatch second = sweepOnce();
        assertThat(second.attempted()).isGreaterThanOrEqualTo(1);
        assertThat(availabilityMapper.selectByListingId(listing.getId()).getAvailability())
                .isEqualTo(ListingAvailability.UNAVAILABLE.getCode());
    }

    // ------------------------------------------------------------------ 夹具

    /** 单批次推进：候选不超过批大小(2)时即为一整圈遍历；扫描本身就是fixed-delay循环覆盖，不设“settle”终态。 */
    private BuyerSelectionCatalogSweepService.SweepBatch sweepOnce() {
        return sweepService.sweepOneBatch();
    }

    private void changeStatus(Long listingId, BuyerListingStatus target) {
        BuyerListingDO fresh = listingMapper.selectById(listingId);
        publication.changeStatus(new BuyerListingPublicationService.ChangeListingStatusCommand(
                fresh.getListingNo(), fresh.getVersion(), target, 7L, "trace-" + sequence, null, null));
    }

    private void buildBrokenListing() {
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        String skuCode = "SKU_%03d_200".formatted(sequence);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(skuCode, spuCode(),
                "损坏SKU" + sequence, "DIRECT_RECHARGE", 1000L, "CNY", 1L));
        catalog.changeSkuStatus(skuCode, CatalogEntityStatus.ACTIVE, 1L);
        PlatformSkuDO sku = context.getBean(PlatformSkuMapper.class).selectByCode(skuCode);
        jdbc.update("UPDATE sc_platform_sku SET market_price=1000, suggested_sale_price=1000 WHERE id=?", sku.getId());
        LocalDateTime now = context.getBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class).now();
        BuyerProductDO brokenProduct = BuyerProductDO.builder().buyerProductCode("BP" + sequence).buyerId(buyer.getId())
                .platformSkuId(sku.getId()).status((short) 10).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(BuyerProductMapper.class).insert(brokenProduct);
        brokenListing = BuyerListingDO.builder().listingNo("LSTB" + sequence).buyerId(buyer.getId())
                .platformSkuId(sku.getId()).buyerSkuCode(brokenProduct.getBuyerProductCode())
                .managementMode("AUTO_SELECTION").buyerProductId(brokenProduct.getId())
                .status(BuyerListingStatus.DRAFT.getCode()).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        listingMapper.insert(brokenListing);
        availabilityMapper.insert(BuyerListingAvailabilityDO.builder().listingId(brokenListing.getId())
                .availability(ListingAvailability.UNKNOWN.getCode()).reasonCode("NOT_EVALUATED")
                .basisVersion(0L).version(0L).evaluatedAt(now).updatedAt(now).build());
        // 损坏引用：手动创建真实定价身份行并指向不存在的版本（不经初始化流程，直接制造损坏指针）。
        context.getBean(com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper.class)
                .insert(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO.builder()
                        .buyerProductId(brokenProduct.getId())
                        .currentVersionId(999999L)
                        .pricingStatus("READY")
                        .version(0L)
                        .createdAt(now).updatedAt(now)
                        .build());
    }

    private String spuCode() {
        return jdbc.queryForObject("""
                SELECT spu.spu_code FROM sc_platform_sku sku
                JOIN sc_platform_spu spu ON spu.id = sku.spu_id WHERE sku.id=?
                """, String.class, listing.getPlatformSkuId());
    }

    private void buildFixture() {
        for (String table : List.of("sc_buyer_catalog_change", "sc_buyer_listing_availability",
                "sc_buyer_listing_version", "sc_buyer_listing",
                "sc_buyer_sku_pricing_version", "sc_buyer_sku_pricing", "sc_buyer_product",
                "sc_project_listing_version", "sc_project_listing", "sc_buyer_project",
                "sc_supplier_offer_version", "sc_supplier_offer", "sc_inventory_source",
                "sc_supplier_connector_version", "sc_supplier_connector", "sc_credential_slot",
                "sc_supplier", "sc_buyer", "sc_std_routing_rule_scope", "sc_std_routing_candidate",
                "sc_std_routing_rule", "sc_supplier_routing_policy_version", "sc_supplier_routing_policy")) {
            jdbc.update("DELETE FROM " + table);
        }
        // 每测试重建扫描实例：内存位置与遍历上界不跨测试残留，模拟调度器单实例语义由单元测试覆盖。
        sweepService = new BuyerSelectionCatalogSweepService(listingMapper, refreshService,
                context.getBean(com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties.class));
        LocalDateTime now = context.getBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class).now();
        String suffix = Long.toString(++sequence);
        String buyerCode = context.getBean(BuyerCommandService.class).createBuyer(
                new BuyerCommandService.CreateBuyerCommand(
                        null, "扫描买家" + suffix, "扫描买家" + suffix, null, null, "AIFULI_MALL",
                        "sweep-" + suffix, now.minusMinutes(1), 1L));
        buyer = context.getBean(BuyerMapper.class).selectByBuyerCode(buyerCode);
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        String brand = "BRD_%03d".formatted(sequence), category = "CAT_%03d".formatted(sequence);
        String spuCode = "SPU_%03d".formatted(sequence);
        String skuCode = "SKU_%03d_100".formatted(sequence);
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(brand, brand, null, null, 0, 1L));
        catalog.changeBrandStatus(brand, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(category, category, null, 0, 1L));
        catalog.changeCategoryStatus(category, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(spuCode, brand, category,
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", spuCode, null, null, "不可自动撤销", 1L));
        catalog.changeSpuStatus(spuCode, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(skuCode, spuCode, "扫描SKU" + suffix,
                "DIRECT_RECHARGE", 1000L, "CNY", 1L));
        catalog.changeSkuStatus(skuCode, CatalogEntityStatus.ACTIVE, 1L);
        PlatformSkuDO sku = context.getBean(PlatformSkuMapper.class).selectByCode(skuCode);
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
        supplier = supply("SP" + suffix);
        GenericBatchPricingService pricing = context.getBean(GenericBatchPricingService.class);
        pricing.initializePlatformDefaults(new PlatformSkuPricingInitializationEvent(List.of(skuCode), 1L));
        pricing.initializeBuyerProduct(new BuyerProductPricingInitializationEvent(product.getId(), 1L));
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
                "SWEEP" + sequence, "扫描策略", "V1", LocalDateTime.now().minusMinutes(1), null, rules, 1L, 40, 30, 30));
        publicationService.publish(new RoutingPolicyPublicationService.PublishCommand(draft.policyVersionId(), 0L,
                "扫描夹具", 1L, "fixture", null, null));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxEventRepository.class, BuyerCommandService.class,
            ProductCatalogCommandService.class, ProductCenterTestConfiguration.class,
            RoutingPolicyPublicationService.class, SupplierRoutingApplicationService.class,
            GenericBatchPricingService.class, BuyerListingPublicationService.class,
            com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties.class})
    @ComponentScan(basePackages = "com.aifuli.module.supplychain.tradecore.catalog.application",
            useDefaultFilters = false, includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = ".*(CurrentSupplyEligibilityReader|BuyerSelectionCatalogRefreshService"
                            + "|BuyerSelectionCatalogRefreshTransactionalWorker|BuyerSelectionCatalogSweepService)"))
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
