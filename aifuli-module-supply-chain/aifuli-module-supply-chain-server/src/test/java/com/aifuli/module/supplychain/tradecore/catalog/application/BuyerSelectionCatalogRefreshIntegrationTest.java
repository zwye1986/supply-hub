package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
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
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerCatalogChangeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerCatalogChangeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerCatalogRefreshOutcome;
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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_NOT_READY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 独占 Testcontainers 空库，经真实 Liquibase／事务代理／MyBatis 证明单 LST 自动刷新的锁序、幂等与决定表。
 * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyerSelectionCatalogRefreshIntegrationTest {

    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private final RefreshSqlProbe sqlProbe = new RefreshSqlProbe();

    private BuyerSelectionCatalogRefreshService service;
    private BuyerListingPublicationService publication;
    private GenericBatchPricingService pricingService;
    private BuyerCatalogProperties properties;
    private BuyerListingMapper listingMapper;
    private BuyerListingVersionMapper versionMapper;
    private BuyerListingAvailabilityMapper availabilityMapper;
    private BuyerCatalogChangeMapper changeMapper;
    private BuyerProductMapper productMapper;
    private BuyerSkuPricingMapper buyerPricingMapper;
    private BuyerSkuPricingVersionMapper buyerVersionMapper;
    private BuyerProjectMapper projectMapper;
    private ProjectListingMapper projectListingMapper;
    private PlatformSkuMapper skuMapper;

    private LocalDateTime now;
    private long sequence;
    private BuyerDO buyer;
    private String buyerCode;
    private PlatformSkuDO sku;
    private String skuCode;
    private String spuCode;
    private String skuName;
    private BuyerProductDO product;
    private BuyerListingDO listing;
    private SupplierDO supplier;
    private BuyerProjectDO projectA;
    private BuyerProjectDO projectB;
    private LocalDateTime offerValidTo;

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
        context.getBean("supplyChainSqlSessionFactory", org.apache.ibatis.session.SqlSessionFactory.class)
                .getConfiguration().addInterceptor(sqlProbe);
        service = context.getBean(BuyerSelectionCatalogRefreshService.class);
        publication = context.getBean(BuyerListingPublicationService.class);
        pricingService = context.getBean(GenericBatchPricingService.class);
        properties = context.getBean(BuyerCatalogProperties.class);
        listingMapper = context.getBean(BuyerListingMapper.class);
        versionMapper = context.getBean(BuyerListingVersionMapper.class);
        availabilityMapper = context.getBean(BuyerListingAvailabilityMapper.class);
        changeMapper = context.getBean(BuyerCatalogChangeMapper.class);
        productMapper = context.getBean(BuyerProductMapper.class);
        buyerPricingMapper = context.getBean(BuyerSkuPricingMapper.class);
        buyerVersionMapper = context.getBean(BuyerSkuPricingVersionMapper.class);
        projectMapper = context.getBean(BuyerProjectMapper.class);
        projectListingMapper = context.getBean(ProjectListingMapper.class);
        skuMapper = context.getBean(PlatformSkuMapper.class);
    }

    @AfterAll
    void stop() {
        if (context != null) context.close();
        mysql.stop();
    }

    @BeforeEach
    void fixture() {
        buildFixture();
    }

    @AfterEach
    void releaseProbe() {
        sqlProbe.failure = null;
        sqlProbe.statements.clear();
    }

    /**
     * 全量重建一套真实夹具：买家／SKU／BPD／AUTO DRAFT LST＋UNKNOWN 投影／有效供给／READY 价格／双项目PLT与专属路由。
     * 仅供本类独占测试库使用，不访问已有业务库。
     */
    private void buildFixture() {
        for (String table : List.of("sc_std_routing_rule_scope", "sc_std_routing_candidate", "sc_std_routing_rule",
                "sc_supplier_routing_policy_version", "sc_supplier_routing_policy")) {
            jdbc.update("DELETE FROM " + table);
        }
        now = context.getBean(SupplyChainDatabaseClock.class).now();
        String suffix = Long.toString(++sequence);
        buyerCode = context.getBean(BuyerCommandService.class).createBuyer(new BuyerCommandService.CreateBuyerCommand(
                null, "刷新买家" + suffix, "刷新买家" + suffix, null, null, "AIFULI_MALL",
                "refresh-" + suffix, now.minusMinutes(1), 1L));
        buyer = context.getBean(BuyerMapper.class).selectByBuyerCode(buyerCode);
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        String brand = "BRD_%03d".formatted(sequence), category = "CAT_%03d".formatted(sequence);
        spuCode = "SPU_%03d".formatted(sequence);
        skuCode = "SKU_%03d_100".formatted(sequence);
        skuName = "刷新SKU" + suffix;
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(brand, brand, null, null, 0, 1L));
        catalog.changeBrandStatus(brand, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(category, category, null, 0, 1L));
        catalog.changeCategoryStatus(category, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(spuCode, brand, category,
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", spuCode, null, null, "不可自动撤销", 1L));
        catalog.changeSpuStatus(spuCode, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(skuCode, spuCode, skuName,
                "DIRECT_RECHARGE", 1000L, "CNY", 1L));
        catalog.changeSkuStatus(skuCode, CatalogEntityStatus.ACTIVE, 1L);
        sku = skuMapper.selectByCode(skuCode);
        // 兼容入口的第五参是faceValue；真实默认定价依赖市场价，此处补真实市场价与建议价。
        jdbc.update("UPDATE sc_platform_sku SET market_price=1000, suggested_sale_price=1000 WHERE id=?", sku.getId());
        sku = skuMapper.selectByCode(skuCode);
        product = BuyerProductDO.builder().buyerProductCode("EP" + suffix).buyerId(buyer.getId())
                .platformSkuId(sku.getId()).status((short) 10).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        productMapper.insert(product);
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
        pricingService.initializePlatformDefaults(new PlatformSkuPricingInitializationEvent(List.of(skuCode), 1L));
        pricingService.initializeBuyerProduct(new BuyerProductPricingInitializationEvent(product.getId(), 1L));
        projectA = project("PA" + suffix);
        projectB = project("PB" + suffix);
        projectListing(projectA);
        projectListing(projectB);
        publishRules(List.of(fixed("RA" + suffix, supplier.getSupplierCode(), projectA.getProjectCode()),
                fixed("RB" + suffix, supplier.getSupplierCode(), projectB.getProjectCode())));
        offerValidTo = jdbc.queryForObject("""
                SELECT v.valid_to FROM sc_supplier_offer_version v
                JOIN sc_supplier_offer o ON o.id = v.offer_id WHERE o.supplier_id=?
                """, LocalDateTime.class, supplier.getId());
        sqlProbe.statements.clear();
    }

    // ------------------------------------------------------------------ 发布与幂等

    @Test
    void draftWithReadyPriceAndConfirmedQualificationPublishesRealCommercialSnapshot() {
        BuyerSelectionCatalogRefreshResult result = refresh(7L);

        assertThat(result.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.UPDATED);
        assertThat(result.publicationState()).isEqualTo(BuyerListingStatus.PUBLISHED);
        assertThat(result.availabilityStatus()).isEqualTo(ListingAvailability.AVAILABLE);
        assertThat(result.reasonCode()).isNull();
        assertThat(result.evaluatedAt()).isNotNull().isAfterOrEqualTo(now);

        BuyerListingDO after = listingMapper.selectById(listing.getId());
        assertThat(after.getStatus()).isEqualTo(BuyerListingStatus.PUBLISHED.getCode());
        assertThat(after.getCurrentVersionId()).isNotNull();
        BuyerListingVersionDO version = versionMapper.selectById(after.getCurrentVersionId());
        BuyerSkuPricingDO pricing = buyerPricingMapper.selectByBuyerProductId(product.getId());
        assertThat(version.getSourceBuyerPricingVersionId()).isEqualTo(pricing.getCurrentVersionId());
        assertThat(version.getBuyerSettlementUnitPrice()).isEqualTo(1000L);
        assertThat(version.getCurrency()).isEqualTo("CNY");
        assertThat(version.getDisplayName()).isEqualTo(skuName);
        assertThat(version.getMinQuantity()).isEqualTo(1L);
        assertThat(version.getMaxQuantity()).isEqualTo(1L);
        assertThat(version.getValidFrom()).isAfterOrEqualTo(now);
        assertThat(version.getValidTo()).isNull();
        assertThat(version.getPublishedBy()).isEqualTo(7L);
        assertThat(version.getCommercialSnapshotDigest()).hasSize(32);

        BuyerListingAvailabilityDO availability = availabilityMapper.selectByListingId(listing.getId());
        assertThat(availability.getAvailability()).isEqualTo(ListingAvailability.AVAILABLE.getCode());
        assertThat(availability.getReasonCode()).isEmpty();
        assertThat(availability.getBasisDigest()).hasSize(32);
        assertThat(availability.getBasisJson()).contains(String.valueOf(pricing.getCurrentVersionId()));
        // 投影陈旧安全：期限=min(未来事实边界, evaluatedAt+30s)，夹具Offer边界为1天后，30秒上限生效。见设计 §6.4。
        assertThat(availability.getValidUntil()).isEqualTo(availability.getEvaluatedAt().plusSeconds(30));

        assertThat(listingVersionCount()).isEqualTo(1);
        assertThat(listingChangeCount()).isEqualTo(1);
        BuyerCatalogChangeDO change = changeMapper.selectOne(new LambdaQueryWrapper<BuyerCatalogChangeDO>()
                .eq(BuyerCatalogChangeDO::getListingId, listing.getId()));
        assertThat(change.getChangeType()).isEqualTo("UPSERT");
        assertThat(change.getListingVersionId()).isEqualTo(version.getId());
        assertThat(change.getAvailabilityVersion()).isEqualTo(availability.getVersion());
        assertThat(fanoutCount(listing.getListingNo())).isZero();
        assertLockScopeAndWritesAreBounded();
    }

    @Test
    void repeatedRefreshKeepsSingleVersionAndCursorWhileAdvancingEvaluation() throws Exception {
        refresh(7L);
        BuyerListingAvailabilityDO first = availabilityMapper.selectByListingId(listing.getId());
        Thread.sleep(20);

        BuyerSelectionCatalogRefreshResult second = refresh(7L);

        assertThat(second.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.UNCHANGED);
        assertThat(second.availabilityStatus()).isEqualTo(ListingAvailability.AVAILABLE);
        assertThat(listingVersionCount()).isEqualTo(1);
        assertThat(listingChangeCount()).isEqualTo(1);
        BuyerListingAvailabilityDO after = availabilityMapper.selectByListingId(listing.getId());
        assertThat(after.getEvaluatedAt()).isAfter(first.getEvaluatedAt());
        assertThat(after.getVersion()).isEqualTo(first.getVersion() + 1);
    }

    @Test
    void concurrentRefreshSameSourcePublishesExactlyOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var action = (java.util.concurrent.Callable<BuyerSelectionCatalogRefreshResult>) () -> {
                start.await();
                return service.refresh(listing.getId(), BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 7L);
            };
            var first = pool.submit(action);
            var second = pool.submit(action);
            start.countDown();
            List<BuyerSelectionCatalogRefreshResult> results = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results).filteredOn(item -> item.refreshOutcome() == BuyerCatalogRefreshOutcome.UPDATED)
                    .hasSize(1);
            assertThat(results).allSatisfy(item -> assertThat(item.refreshOutcome())
                    .isIn(BuyerCatalogRefreshOutcome.UPDATED, BuyerCatalogRefreshOutcome.UNCHANGED));
        }
        assertThat(listingVersionCount()).isEqualTo(1);
        assertThat(listingChangeCount()).isEqualTo(1);
        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.PUBLISHED.getCode());
    }

    @Test
    void lateRefreshReadsCurrentSourceInsteadOfReplayingOldSnapshot() {
        refresh(7L);
        pricingService.savePlatform(List.of(skuCode), PlatformDefaultPricingPolicy.create(), 1L);
        BuyerSkuPricingDO pricing = buyerPricingMapper.selectByBuyerProductId(product.getId());

        BuyerSelectionCatalogRefreshResult result = refresh(7L);

        assertThat(result.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.UPDATED);
        assertThat(listingVersionCount()).isEqualTo(2);
        BuyerListingDO after = listingMapper.selectById(listing.getId());
        BuyerListingVersionDO latest = versionMapper.selectById(after.getCurrentVersionId());
        assertThat(latest.getId()).isNotEqualTo(versionMapper.selectByListingAndVersionNo(listing.getId(), 1).getId());
        assertThat(latest.getSourceBuyerPricingVersionId()).isEqualTo(pricing.getCurrentVersionId());
        assertThat(fanoutCount(listing.getListingNo())).isZero();
    }

    // ------------------------------------------------------------------ 生命周期

    @Test
    void manualListingIsSkippedWithZeroWrites() {
        String manualSku = "SKU_%03d_200".formatted(sequence);
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(manualSku, spuCode,
                "手工SKU" + sequence, "DIRECT_RECHARGE", 1000L, "CNY", 1L));
        catalog.changeSkuStatus(manualSku, CatalogEntityStatus.ACTIVE, 1L);
        String manualNo = catalog.createBuyerListing(new ProductCatalogCommandService.CreateBuyerListingCommand(
                buyerCode, manualSku, "手工别名" + sequence, 1L));
        BuyerListingDO manual = listingMapper.selectByListingNo(manualNo);
        Map<String, Object> before = commercialState(manual.getId());

        BuyerSelectionCatalogRefreshResult result = service.refresh(manual.getId(),
                BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 7L);

        assertThat(result.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.SKIPPED_MANUAL);
        assertThat(result.publicationState()).isEqualTo(BuyerListingStatus.DRAFT);
        assertThat(result.availabilityStatus()).isEqualTo(ListingAvailability.UNKNOWN);
        assertThat(commercialState(manual.getId())).isEqualTo(before);
    }

    @Test
    void retiredAutoIsTerminalAndNeverRevivedByLaterFacts() {
        refresh(7L);
        changeStatus(listing.getId(), BuyerListingStatus.RETIRED, 7L);
        Map<String, Object> before = commercialState(listing.getId());
        jdbc.update("UPDATE sc_supplier SET status=20 WHERE id=?", supplier.getId());

        BuyerSelectionCatalogRefreshResult result = refresh(7L);

        assertThat(result.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.TERMINAL);
        assertThat(result.publicationState()).isEqualTo(BuyerListingStatus.RETIRED);
        assertThat(commercialState(listing.getId())).isEqualTo(before);
    }

    @Test
    void suspendedAutoOnlyEvaluatesSafetyProjectionAndManualRecoveryRechecksUnderLock() {
        refresh(7L);
        BuyerListingDO published = listingMapper.selectById(listing.getId());
        changeStatus(published.getId(), BuyerListingStatus.SUSPENDED, 7L);

        BuyerSelectionCatalogRefreshResult paused = refresh(7L);

        assertThat(paused.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.PAUSED);
        assertThat(paused.publicationState()).isEqualTo(BuyerListingStatus.SUSPENDED);
        assertThat(paused.availabilityStatus()).isEqualTo(ListingAvailability.AVAILABLE);
        assertThat(listingVersionCount()).isEqualTo(1);
        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.SUSPENDED.getCode());

        jdbc.update("UPDATE sc_supplier SET status=20 WHERE id=?", supplier.getId());
        assertThatThrownBy(() -> changeStatus(listing.getId(), BuyerListingStatus.PUBLISHED, 7L))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_CATALOG_NOT_READY.getCode());
        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.SUSPENDED.getCode());
        jdbc.update("UPDATE sc_supplier SET status=10 WHERE id=?", supplier.getId());

        pricingService.savePlatform(List.of(skuCode), PlatformDefaultPricingPolicy.create(), 1L);
        BuyerSkuPricingDO pricing = buyerPricingMapper.selectByBuyerProductId(product.getId());
        Long firstPlatformVersion = jdbc.queryForObject("""
                SELECT id FROM sc_platform_sku_pricing_version WHERE platform_sku_id=?
                ORDER BY version_no ASC LIMIT 1
                """, Long.class, sku.getId());
        jdbc.update("UPDATE sc_buyer_sku_pricing_version SET platform_pricing_version_id=? WHERE id=?",
                firstPlatformVersion, pricing.getCurrentVersionId());
        assertThatThrownBy(() -> changeStatus(listing.getId(), BuyerListingStatus.PUBLISHED, 7L))
                .as("人工恢复不得在持LST锁时委派重算，依赖前移必须失败关闭")
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_CATALOG_NOT_READY.getCode());
        jdbc.update("UPDATE sc_buyer_sku_pricing_version SET platform_pricing_version_id=? WHERE id=?",
                jdbc.queryForObject("SELECT current_version_id FROM sc_platform_sku_pricing WHERE platform_sku_id=?",
                        Long.class, sku.getId()),
                pricing.getCurrentVersionId());

        Map<String, Object> projectionBefore = commercialState(listing.getId());
        changeStatus(listing.getId(), BuyerListingStatus.PUBLISHED, 7L);

        BuyerListingDO resumed = listingMapper.selectById(listing.getId());
        assertThat(resumed.getStatus()).isEqualTo(BuyerListingStatus.PUBLISHED.getCode());
        assertThat(resumed.getCurrentVersionId()).isNotNull();
        assertThat(jdbc.queryForList("SELECT id FROM sc_buyer_listing_version WHERE listing_id=?",
                Long.class, listing.getId())).hasSize(1);
        List<Map<String, Object>> resumedVersions = jdbc.queryForList(
                "SELECT * FROM sc_buyer_listing_version WHERE listing_id=?", listing.getId());
        assertThat(resumedVersions).hasSize(1);
        assertThat(resumed.getVersion()).isEqualTo(snapshotListingVersion(projectionBefore) + 1);
        assertThat(fanoutCount(listing.getListingNo())).isZero();
    }

    private int snapshotListingVersion(Map<String, Object> state) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) state.get("listing");
        return ((Number) row.get("version")).intValue();
    }

    @Test
    void manualResumeDoesNotRepublishVersionButNextRefreshDoes() {
        refresh(7L);
        BuyerListingDO published = listingMapper.selectById(listing.getId());
        changeStatus(published.getId(), BuyerListingStatus.SUSPENDED, 7L);
        pricingService.savePlatform(List.of(skuCode), PlatformDefaultPricingPolicy.create(), 1L);

        changeStatus(listing.getId(), BuyerListingStatus.PUBLISHED, 7L);
        assertThat(listingVersionCount()).isEqualTo(1);

        BuyerSelectionCatalogRefreshResult result = refresh(7L);

        assertThat(result.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.UPDATED);
        assertThat(listingVersionCount()).isEqualTo(2);
        BuyerSkuPricingDO pricing = buyerPricingMapper.selectByBuyerProductId(product.getId());
        BuyerListingVersionDO latest = versionMapper.selectById(
                listingMapper.selectById(listing.getId()).getCurrentVersionId());
        assertThat(latest.getSourceBuyerPricingVersionId()).isEqualTo(pricing.getCurrentVersionId());
    }

    @Test
    void manualRecoveryKeepsOriginalManualSemantics() {
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        String manualSku = "SKU_%03d_200".formatted(sequence);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(manualSku, spuCode,
                "手工恢复SKU" + sequence, "DIRECT_RECHARGE", 1000L, "CNY", 1L));
        catalog.changeSkuStatus(manualSku, CatalogEntityStatus.ACTIVE, 1L);
        String manualNo = catalog.createBuyerListing(new ProductCatalogCommandService.CreateBuyerListingCommand(
                buyerCode, manualSku, "手工恢复别名" + sequence, 1L));
        publication.publish(new BuyerListingPublicationService.PublishListingCommand(manualNo, 0L, 500L,
                "手工商业", now.minusMinutes(1), null, "人工发布", 7L, "trace-" + sequence, null, null));
        BuyerListingDO manual = listingMapper.selectByListingNo(manualNo);
        changeStatus(manual.getId(), BuyerListingStatus.SUSPENDED, 7L);

        changeStatus(manual.getId(), BuyerListingStatus.PUBLISHED, 7L);

        BuyerListingDO resumed = listingMapper.selectById(manual.getId());
        assertThat(resumed.getStatus()).isEqualTo(BuyerListingStatus.PUBLISHED.getCode());
        assertThat(resumed.getCurrentVersionId()).isEqualTo(manual.getCurrentVersionId());
    }

    // ------------------------------------------------------------------ 等待与恢复

    @Test
    void draftWithoutReadyPriceWaitsWithRealReasonAndUnavailableProjection() {
        jdbc.update("UPDATE sc_buyer_sku_pricing SET pricing_status='UNPRICED' WHERE buyer_product_id=?",
                product.getId());

        BuyerSelectionCatalogRefreshResult result = refresh(7L);

        assertThat(result.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.DEFERRED);
        assertThat(result.reasonCode()).isEqualTo(BuyerSelectionCatalogRefreshResult.Reason.PRICING_NOT_READY);
        assertThat(result.availabilityStatus()).isEqualTo(ListingAvailability.UNAVAILABLE);
        assertThat(listingMapper.selectById(listing.getId()).getStatus()).isEqualTo(BuyerListingStatus.DRAFT.getCode());
        assertThat(listingVersionCount()).isZero();
    }

    @Test
    void publishedListingKeepsHistoryWhenSupplyDisappearsAndRestoresAutomatically() {
        refresh(7L);
        BuyerListingDO published = listingMapper.selectById(listing.getId());
        jdbc.update("UPDATE sc_supplier SET status=20 WHERE id=?", supplier.getId());

        BuyerSelectionCatalogRefreshResult degraded = refresh(7L);

        assertThat(degraded.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.DEFERRED);
        assertThat(degraded.reasonCode()).isEqualTo(BuyerSelectionCatalogRefreshResult.Reason.QUALIFICATION_UNAVAILABLE);
        assertThat(degraded.publicationState()).isEqualTo(BuyerListingStatus.PUBLISHED);
        assertThat(degraded.availabilityStatus()).isEqualTo(ListingAvailability.UNAVAILABLE);
        assertThat(listingVersionCount()).isEqualTo(1);
        assertThat(listingMapper.selectById(listing.getId()).getCurrentVersionId())
                .isEqualTo(published.getCurrentVersionId());

        jdbc.update("UPDATE sc_supplier SET status=10 WHERE id=?", supplier.getId());

        BuyerSelectionCatalogRefreshResult restored = refresh(7L);
        assertThat(restored.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.UPDATED);
        assertThat(restored.availabilityStatus()).isEqualTo(ListingAvailability.AVAILABLE);
        assertThat(listingVersionCount()).isEqualTo(1);
    }

    @Test
    void buyerProductRemovalAndRevivalKeepIdentityAndOnlyProjectionChanges() {
        refresh(7L);
        Long listingId = listing.getId();
        jdbc.update("UPDATE sc_buyer_product SET status=90 WHERE id=?", product.getId());

        BuyerSelectionCatalogRefreshResult removed = refresh(7L);

        assertThat(removed.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.DEFERRED);
        assertThat(removed.reasonCode()).isEqualTo(BuyerSelectionCatalogRefreshResult.Reason.BUYER_PRODUCT_REMOVED);
        assertThat(removed.availabilityStatus()).isEqualTo(ListingAvailability.UNAVAILABLE);
        assertThat(listingMapper.selectById(listingId).getStatus()).isEqualTo(BuyerListingStatus.PUBLISHED.getCode());
        assertThat(availabilityMapper.selectByListingId(listingId).getReasonCode()).isEqualTo("BUYER_PRODUCT_REMOVED");
        assertThat(listingVersionCount()).isEqualTo(1);

        jdbc.update("UPDATE sc_buyer_product SET status=10 WHERE id=?", product.getId());

        BuyerSelectionCatalogRefreshResult revived = refresh(7L);
        assertThat(revived.availabilityStatus()).isEqualTo(ListingAvailability.AVAILABLE);
        assertThat(listingMapper.selectById(listingId).getId()).isEqualTo(listingId);
        assertThat(listingMapper.selectById(listingId).getBuyerProductId()).isEqualTo(product.getId());
    }

    @Test
    void projectListingRemovalAffectsOnlyItsOwnProjectAggregation() {
        refresh(7L);
        Long projectBListing = projectListingMapper.selectByProjectAndBuyerProduct(
                projectB.getId(), product.getId()).getId();
        jdbc.update("UPDATE sc_project_listing SET status=90 WHERE id=?", projectBListing);

        BuyerSelectionCatalogRefreshResult otherProjectRemoved = refresh(7L);

        assertThat(otherProjectRemoved.availabilityStatus()).isEqualTo(ListingAvailability.AVAILABLE);
        assertThat(availabilityMapper.selectByListingId(listing.getId()).getAvailability())
                .isEqualTo(ListingAvailability.AVAILABLE.getCode());
        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.PUBLISHED.getCode());

        Long projectAListing = projectListingMapper.selectByProjectAndBuyerProduct(
                projectA.getId(), product.getId()).getId();
        jdbc.update("UPDATE sc_project_listing SET status=90 WHERE id=?", projectAListing);

        BuyerSelectionCatalogRefreshResult allRemoved = refresh(7L);
        assertThat(allRemoved.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.DEFERRED);
        assertThat(allRemoved.reasonCode()).isEqualTo(BuyerSelectionCatalogRefreshResult.Reason.QUALIFICATION_UNAVAILABLE);
        assertThat(allRemoved.availabilityStatus()).isEqualTo(ListingAvailability.UNAVAILABLE);
        assertThat(listingVersionCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 技术失败与回滚

    @Test
    void manualRecoveryOfDraftAutoWithoutVersionIsRejectedAsNotReady() {
        assertThatThrownBy(() -> changeStatus(listing.getId(), BuyerListingStatus.PUBLISHED, 7L))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_CATALOG_NOT_READY.getCode());
        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.DRAFT.getCode());
        assertThat(listingVersionCount()).isZero();
        assertThat(listingChangeCount()).isZero();
    }

    @Test
    void manualRecoveryWithBrokenPricingReferenceFailsTechnicallyWithoutWrite() {
        refresh(7L);
        changeStatus(listing.getId(), BuyerListingStatus.SUSPENDED, 7L);
        jdbc.update("UPDATE sc_buyer_sku_pricing SET current_version_id=999999 WHERE buyer_product_id=?",
                product.getId());
        Map<String, Object> before = commercialState(listing.getId());

        assertThatThrownBy(() -> changeStatus(listing.getId(), BuyerListingStatus.PUBLISHED, 7L))
                .isInstanceOf(IllegalStateException.class);

        assertThat(commercialState(listing.getId())).isEqualTo(before);
        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.SUSPENDED.getCode());
    }

    @Test
    void manualRecoveryRefreshesSafetyProjectionInSameTransaction() {
        refresh(7L);
        changeStatus(listing.getId(), BuyerListingStatus.SUSPENDED, 7L);
        // 用真实刷新路径制造 UNAVAILABLE 投影（basis摘要与投影行同步），再恢复供给后人工恢复。
        jdbc.update("UPDATE sc_supplier SET status=20 WHERE id=?", supplier.getId());
        BuyerSelectionCatalogRefreshResult degraded = refresh(7L);
        assertThat(degraded.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.PAUSED);
        assertThat(degraded.availabilityStatus()).isEqualTo(ListingAvailability.UNAVAILABLE);
        jdbc.update("UPDATE sc_supplier SET status=10 WHERE id=?", supplier.getId());
        BuyerListingAvailabilityDO before = availabilityMapper.selectByListingId(listing.getId());

        changeStatus(listing.getId(), BuyerListingStatus.PUBLISHED, 7L);

        BuyerListingAvailabilityDO after = availabilityMapper.selectByListingId(listing.getId());
        assertThat(after.getAvailability()).isEqualTo(ListingAvailability.AVAILABLE.getCode());
        assertThat(after.getReasonCode()).isEmpty();
        assertThat(after.getEvaluatedAt()).isAfterOrEqualTo(before.getEvaluatedAt());
        assertThat(after.getValidUntil()).isEqualTo(after.getEvaluatedAt().plusSeconds(30));
        assertThat(after.getBasisVersion()).isEqualTo(before.getBasisVersion() + 1);
        assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
        assertThat(fanoutCount(listing.getListingNo())).isZero();
    }

    @Test
    void projectionMissingOnManualRecoveryFailsTechnically() {
        refresh(7L);
        changeStatus(listing.getId(), BuyerListingStatus.SUSPENDED, 7L);
        jdbc.update("DELETE FROM sc_buyer_listing_availability WHERE listing_id=?", listing.getId());

        assertThatThrownBy(() -> changeStatus(listing.getId(), BuyerListingStatus.PUBLISHED, 7L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.SUSPENDED.getCode());
    }

    @Test
    void refreshWithMissingCurrentVersionReferenceFailsTechnically() {
        refresh(7L);
        jdbc.update("UPDATE sc_buyer_listing SET current_version_id=999999 WHERE id=?", listing.getId());
        Map<String, Object> before = commercialState(listing.getId());

        assertThatThrownBy(() -> refresh(7L)).isInstanceOf(IllegalStateException.class);

        assertThat(commercialState(listing.getId())).isEqualTo(before);
    }

    @Test
    void brokenPricingReferenceFailsTechnicallyWithoutInventingWaiting() {
        Map<String, Object> before = commercialState(listing.getId());
        jdbc.update("UPDATE sc_buyer_sku_pricing SET current_version_id=999999 WHERE buyer_product_id=?",
                product.getId());

        assertThatThrownBy(() -> refresh(7L)).isInstanceOf(IllegalStateException.class);

        assertThat(commercialState(listing.getId())).isEqualTo(before);
        assertThat(listingVersionCount()).isZero();
    }

    @Test
    void technicalFailureAtAnyWriteStepRollsBackWholeRefresh() {
        assertRolledBackWhen("BuyerListingVersionMapper.insert");
        assertRolledBackWhen("BuyerListingMapper.update");
        assertRolledBackWhen("BuyerListingAvailabilityMapper.update");
        assertRolledBackWhen("BuyerCatalogChangeMapper.insert");
    }

    private void assertRolledBackWhen(String failingStatementSuffix) {
        buildFixture();
        Map<String, Object> before = commercialState(listing.getId());
        sqlProbe.failure = (statement, sql) -> statement.endsWith(failingStatementSuffix)
                ? new IllegalStateException("隔离刷新失败:" + failingStatementSuffix) : null;

        assertThatThrownBy(() -> refresh(7L))
                .hasRootCauseMessage("隔离刷新失败:" + failingStatementSuffix);

        sqlProbe.failure = null;
        assertThat(commercialState(listing.getId())).as(failingStatementSuffix).isEqualTo(before);
        assertThat(listingVersionCount()).as(failingStatementSuffix).isZero();
        assertThat(listingChangeCount()).as(failingStatementSuffix).isZero();
        assertThat(listingMapper.selectById(listing.getId()).getCurrentVersionId()).isNull();
    }

    @Test
    void stalePricingDependencyRecalculatesOnceOutsideListingLockThenPublishes() {
        refresh(7L);
        long buyerVersionsBefore = buyerVersionCount();
        pricingService.savePlatform(List.of(skuCode), PlatformDefaultPricingPolicy.create(), 1L);
        BuyerSkuPricingDO pricing = buyerPricingMapper.selectByBuyerProductId(product.getId());
        Long firstPlatformVersion = jdbc.queryForObject("""
                SELECT id FROM sc_platform_sku_pricing_version WHERE platform_sku_id=?
                ORDER BY version_no ASC LIMIT 1
                """, Long.class, sku.getId());
        jdbc.update("UPDATE sc_buyer_sku_pricing_version SET platform_pricing_version_id=? WHERE id=?",
                firstPlatformVersion, pricing.getCurrentVersionId());
        sqlProbe.statements.clear();

        BuyerSelectionCatalogRefreshResult result = service.refresh(listing.getId(),
                BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 9L);

        assertThat(result.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.UPDATED);
        assertThat(result.availabilityStatus()).isEqualTo(ListingAvailability.AVAILABLE);
        assertThat(buyerVersionCount()).isEqualTo(buyerVersionsBefore + 2);
        BuyerSkuPricingDO refreshed = buyerPricingMapper.selectByBuyerProductId(product.getId());
        BuyerListingVersionDO latest = versionMapper.selectById(
                listingMapper.selectById(listing.getId()).getCurrentVersionId());
        assertThat(latest.getSourceBuyerPricingVersionId()).isEqualTo(refreshed.getCurrentVersionId());
        assertThat(latest.getPublishedBy()).isEqualTo(9L);

        int[] order = statementOrder();
        assertThat(order[0]).isGreaterThanOrEqualTo(0);
        assertThat(order[0]).isLessThan(order[1]);
        assertThat(order[1]).isLessThan(order[2]);
    }

    /** 返回 [首次LST当前锁, 重算价格版本insert, 第二次LST当前锁] 的下标；带尾空格避免把availability表误判为listing。 */
    private int[] statementOrder() {
        int firstLst = -1, recalc = -1, secondLst = -1;
        for (int index = 0; index < sqlProbe.statements.size(); index++) {
            String[] row = sqlProbe.statements.get(index);
            String sql = row[1].toUpperCase(Locale.ROOT);
            if (sql.contains("SC_BUYER_LISTING ") && sql.contains("FOR UPDATE")) {
                if (firstLst < 0) firstLst = index;
                else if (secondLst < 0) secondLst = index;
            }
            if (row[0].endsWith("BuyerSkuPricingVersionMapper.insert") && recalc < 0) {
                recalc = index;
            }
        }
        return new int[]{firstLst, recalc, secondLst};
    }

    @Test
    void missingSystemOperatorDoesNotPublishWithZeroOrFakePublisher() {
        properties.setSystemOperatorId(null);
        try {
            BuyerSelectionCatalogRefreshResult notReady = service.refresh(listing.getId(),
                    BuyerSelectionCatalogRefreshService.Trigger.SWEEP, null);

            assertThat(notReady.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.DEFERRED);
            assertThat(notReady.reasonCode())
                    .isEqualTo(BuyerSelectionCatalogRefreshResult.Reason.SYSTEM_OPERATOR_NOT_CONFIGURED);
            assertThat(listingVersionCount()).isZero();
            assertThat(listingMapper.selectById(listing.getId()).getCurrentVersionId()).isNull();
            assertThat(listingMapper.selectById(listing.getId()).getStatus())
                    .isEqualTo(BuyerListingStatus.DRAFT.getCode());
            assertThat(availabilityMapper.selectByListingId(listing.getId()).getAvailability())
                    .isEqualTo(ListingAvailability.AVAILABLE.getCode());
        } finally {
            properties.setSystemOperatorId(9001L);
        }

        BuyerSelectionCatalogRefreshResult published = service.refresh(listing.getId(),
                BuyerSelectionCatalogRefreshService.Trigger.SWEEP, null);

        assertThat(published.refreshOutcome()).isEqualTo(BuyerCatalogRefreshOutcome.UPDATED);
        BuyerListingVersionDO version = versionMapper.selectById(
                listingMapper.selectById(listing.getId()).getCurrentVersionId());
        assertThat(version.getPublishedBy()).isEqualTo(9001L);
        BuyerSkuPricingVersionDO source = buyerVersionMapper.selectById(version.getSourceBuyerPricingVersionId());
        assertThat(source.getPublishedBy()).as("来源版本发布人独立保留").isEqualTo(1L);
        assertThat(fanoutCount(listing.getListingNo())).isZero();
    }

    // ------------------------------------------------------------------ 夹具与断言辅助

    private BuyerSelectionCatalogRefreshResult refresh(long operatorId) {
        return service.refresh(listing.getId(), BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, operatorId);
    }

    private void changeStatus(Long listingId, BuyerListingStatus target, long operatorId) {
        BuyerListingDO fresh = listingMapper.selectById(listingId);
        publication.changeStatus(new BuyerListingPublicationService.ChangeListingStatusCommand(
                fresh.getListingNo(), fresh.getVersion(), target, operatorId, "trace-" + sequence, null, null));
    }

    private void assertLockScopeAndWritesAreBounded() {
        for (String[] row : sqlProbe.statements) {
            String sql = row[1].toUpperCase(Locale.ROOT);
            if (sql.contains("FOR UPDATE")) {
                assertThat(sql).as(row[0]).containsAnyOf("SC_BUYER_LISTING", "SC_BUYER_PRODUCT",
                        "SC_BUYER_SKU_PRICING", "SC_BUYER_LISTING_AVAILABILITY");
            }
            if (sql.stripLeading().startsWith("INSERT") || sql.stripLeading().startsWith("UPDATE")) {
                assertThat(sql).as(row[0]).containsAnyOf("SC_BUYER_LISTING_VERSION", "SC_BUYER_LISTING ",
                        "SC_BUYER_LISTING_AVAILABILITY", "SC_BUYER_CATALOG_CHANGE");
            }
        }
        assertThat(sqlProbe.statements).anySatisfy(row ->
                assertThat(row[1].toUpperCase(Locale.ROOT)).contains("SC_BUYER_LISTING").contains("FOR UPDATE"));
    }

    private long listingVersionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_listing_version WHERE listing_id=?",
                Long.class, listing.getId());
    }

    private long listingChangeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_catalog_change WHERE listing_id=?",
                Long.class, listing.getId());
    }

    private long fanoutCount(String listingNo) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sc_outbox_event
                WHERE target_name='buyer-catalog-change-fanout' AND aggregate_id=?
                """, Long.class, listingNo);
    }

    private long buyerVersionCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sc_buyer_sku_pricing_version v
                JOIN sc_buyer_sku_pricing p ON p.id = v.pricing_id WHERE p.buyer_product_id=?
                """, Long.class, product.getId());
    }

    private Map<String, Object> commercialState(Long listingId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("listing", sanitize(jdbc.queryForMap(
                "SELECT * FROM sc_buyer_listing WHERE id=?", listingId)));
        state.put("versions", sanitize(jdbc.queryForList(
                "SELECT * FROM sc_buyer_listing_version WHERE listing_id=? ORDER BY id", listingId)));
        state.put("availability", sanitize(jdbc.queryForList(
                "SELECT * FROM sc_buyer_listing_availability WHERE listing_id=? ORDER BY id", listingId)));
        state.put("changes", sanitize(jdbc.queryForList(
                "SELECT * FROM sc_buyer_catalog_change WHERE listing_id=? ORDER BY id", listingId)));
        return state;
    }

    private static Object sanitize(Object value) {
        if (value instanceof byte[] bytes) return HexFormat.of().formatHex(bytes);
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(key, sanitize(item)));
            return converted;
        }
        if (value instanceof List<?> list) return list.stream().map(BuyerSelectionCatalogRefreshIntegrationTest::sanitize).toList();
        return value;
    }

    private BuyerProjectDO project(String code) {
        BuyerProjectDO project = BuyerProjectDO.builder().projectCode(code + sequence).projectName(code)
                .buyerId(buyer.getId()).catalogMode("MANAGED").settlementPartyType("DISTRIBUTOR")
                .status((short) 10).version(0L).createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        projectMapper.insert(project);
        return project;
    }

    private void projectListing(BuyerProjectDO project) {
        ProjectListingDO listing = ProjectListingDO.builder().listingCode("PL" + project.getProjectCode())
                .projectId(project.getId()).buyerProductId(product.getId()).status((short) 10).version(0L)
                .createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        projectListingMapper.insert(listing);
        ProjectListingVersionDO version = ProjectListingVersionDO.builder().listingId(listing.getId())
                .versionNo(1).buyerSettlementUnitPrice(100L).settlementPriceLevel("L2")
                .priceSource("INHERITED").l3AdjustMode("FOLLOW_L2").currency("CNY")
                .publishedBy(1L).publishedAt(now).createdAt(now).build();
        context.getBean(ProjectListingVersionMapper.class).insert(version);
        jdbc.update("UPDATE sc_project_listing SET current_version_id=? WHERE id=?", version.getId(), listing.getId());
    }

    private SupplierDO supply(String code) {
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
                .platformSkuId(sku.getId()).sourceType("REMOTE_ON_DEMAND").externalSourceCode(unique)
                .ownerType("SUPPLIER").status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(InventorySourceMapper.class).insert(inventory);
        SupplierOfferDO offer = SupplierOfferDO.builder().offerNo("OF" + unique).offerCode(unique)
                .supplierId(created.getId()).platformSkuId(sku.getId()).stabilityScore(90).timelinessScore(90)
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

    private RoutingPolicyPublicationService.RuleDraft fixed(String code, String supplier, String project) {
        return new RoutingPolicyPublicationService.RuleDraft(code, code, "FIXED", supplier,
                "ALL", List.of(), project == null ? "ALL" : "SPECIFIED", project == null ? List.of() : List.of(project),
                project == null ? "ALL" : "SPECIFIED", project == null ? List.of() : List.of("EQUITY_PRODUCT"),
                "ALL", List.of(), null, "STABLE", "PRICE", "COMPOSITE", 10, (short) 10, false, List.of());
    }

    private void publishRules(List<RoutingPolicyPublicationService.RuleDraft> rules) {
        RoutingPolicyPublicationService publicationService = context.getBean(RoutingPolicyPublicationService.class);
        var draft = publicationService.createDraft(new RoutingPolicyPublicationService.CreateDraftCommand(
                "REFRESH" + sequence, "刷新策略", "V1", now.minusMinutes(1), null, rules, 1L, 40, 30, 30));
        publicationService.publish(new RoutingPolicyPublicationService.PublishCommand(draft.policyVersionId(), 0L,
                "刷新夹具", 1L, "fixture", null, null));
        sqlProbe.statements.clear();
    }

    /** 只记录真实SQL并在指定语句前抛异常，从不替换SQL结果。 */
    @Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})})
    static class RefreshSqlProbe implements Interceptor {
        interface Failure {
            RuntimeException check(String statement, String sql);
        }

        final List<String[]> statements = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile Failure failure;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            Object parameters = invocation.getArgs()[1];
            String sql = statement.getBoundSql(parameters).getSql();
            statements.add(new String[]{statement.getId(), sql});
            Failure current = failure;
            if (current != null) {
                RuntimeException error = current.check(statement.getId(), sql);
                if (error != null) throw error;
            }
            return invocation.proceed();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxEventRepository.class, BuyerCommandService.class, ProductCatalogCommandService.class,
            ProductCenterTestConfiguration.class, RoutingPolicyPublicationService.class,
            SupplierRoutingApplicationService.class, GenericBatchPricingService.class,
            BuyerListingPublicationService.class,
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
