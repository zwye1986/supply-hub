package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService;
import com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerListingPublicationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogAdminCoordinator;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRefreshService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogSaveRespVO;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理保存提交后编排：真实MySQL验证保存后逐目录刷新、保存失败零刷新、刷新技术失败隔离为FAILED条目、
 * 同LST去重与automaticRecoveryEnabled。设计 §5「管理保存成功与刷新失败契约」。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyerSelectionCatalogAdminIntegrationTest {

    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;

    private BuyerSelectionCatalogAdminCoordinator coordinator;
    private BuyerSelectionCatalogRefreshService refreshService;
    private BuyerListingMapper listingMapper;
    private BuyerListingAvailabilityMapper availabilityMapper;

    private long sequence;
    private BuyerDO buyer;
    private String buyerCode;
    private BuyerProductDO product;
    private BuyerListingDO listing;
    private List<String> projectListingCodes;

    @BeforeAll
    void start() {
        mysql.start();
        context = new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", mysql.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username", mysql.getUsername(),
                        "aifuli.supply-chain.datasource.password", mysql.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver",
                        "aifuli.supply-chain.catalog.system-operator-id", "9001"))
                .run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        coordinator = context.getBean(BuyerSelectionCatalogAdminCoordinator.class);
        refreshService = context.getBean(BuyerSelectionCatalogRefreshService.class);
        listingMapper = context.getBean(BuyerListingMapper.class);
        availabilityMapper = context.getBean(BuyerListingAvailabilityMapper.class);
    }

    @AfterAll
    void stop() {
        if (context != null) context.close();
        mysql.stop();
    }

    @Test
    void buyerPricingSaveReturnsCommittedWithPerListingResults() {
        buildFixture();
        BuyerSelectionCatalogSaveRespVO response = coordinator.saveBuyerPricing(buyerCode,
                List.of(product.getBuyerProductCode()), GenericBatchPricingService.ProjectOverrides.allInherited(), 7L);

        assertThat(response.getSaveState()).isEqualTo("COMMITTED");
        assertThat(response.getSavedCodes()).containsExactly(product.getBuyerProductCode());
        assertThat(response.isAutomaticRecoveryEnabled()).isTrue();
        assertThat(response.getCatalogResults()).hasSize(1);
        BuyerSelectionCatalogSaveRespVO.CatalogRefreshResultVO item = response.getCatalogResults().getFirst();
        assertThat(item.getListingNo()).isEqualTo(listing.getListingNo());
        assertThat(item.getRefreshOutcome()).isEqualTo("UPDATED");
        assertThat(item.getPublicationState()).isEqualTo(BuyerListingStatus.PUBLISHED.name());
        assertThat(item.getAvailabilityStatus()).isEqualTo(ListingAvailability.AVAILABLE.name());
        assertThat(item.getEvaluatedAt()).isNotNull();
        assertThat(item.getTraceId()).isNotBlank();
        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.PUBLISHED.getCode());
    }

    @Test
    void saveFailurePropagatesWithoutAnyRefresh() {
        buildFixture();

        assertThatThrownBy(() -> coordinator.saveBuyerPricing(buyerCode, List.of("BPD_NOT_EXIST"),
                GenericBatchPricingService.ProjectOverrides.allInherited(), 7L))
                .isInstanceOf(RuntimeException.class);

        assertThat(listingMapper.selectById(listing.getId()).getStatus())
                .isEqualTo(BuyerListingStatus.DRAFT.getCode());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_catalog_change WHERE listing_id=?",
                Long.class, listing.getId())).isZero();
    }

    @Test
    void refreshTechnicalFailureIsIsolatedAsFailedEntryWithoutFakeState() {
        buildFixture();
        publishAuto();
        jdbc.update("UPDATE sc_buyer_listing SET current_version_id=999999 WHERE id=?", listing.getId());

        BuyerSelectionCatalogSaveRespVO response = coordinator.saveBuyerPricing(buyerCode,
                List.of(product.getBuyerProductCode()), GenericBatchPricingService.ProjectOverrides.allInherited(), 7L);

        assertThat(response.getSaveState()).isEqualTo("COMMITTED");
        assertThat(response.getSavedCodes()).containsExactly(product.getBuyerProductCode());
        assertThat(response.getCatalogResults()).hasSize(1);
        BuyerSelectionCatalogSaveRespVO.CatalogRefreshResultVO failed = response.getCatalogResults().getFirst();
        assertThat(failed.getRefreshOutcome()).isEqualTo("FAILED");
        assertThat(failed.getReasonCode()).isEqualTo("1050001176");
        // 失败条目不补默认状态与未确认时间，只保留诊断traceId。
        assertThat(failed.getPublicationState()).isNull();
        assertThat(failed.getAvailabilityStatus()).isNull();
        assertThat(failed.getEvaluatedAt()).isNull();
        assertThat(failed.getTraceId()).isNotBlank();
    }

    @Test
    void duplicateBuyerProductCodesRefreshSingleListingOnce() {
        buildFixture();
        publishAuto();

        BuyerSelectionCatalogSaveRespVO response = coordinator.saveBuyerPricing(buyerCode,
                List.of(product.getBuyerProductCode(), product.getBuyerProductCode()),
                GenericBatchPricingService.ProjectOverrides.allInherited(), 7L);

        assertThat(response.getSavedCodes()).containsExactly(product.getBuyerProductCode());
        assertThat(response.getCatalogResults()).hasSize(1);
        assertThat(response.getCatalogResults().getFirst().getListingNo()).isEqualTo(listing.getListingNo());
    }

    // ------------------------------------------------------------------ 夹具

    @Test
    void allSaveEntriesRejectOuterTransactionBeforeDomainWrites() {
        buildFixture();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean("supplyChainTransactionManager", org.springframework.transaction.PlatformTransactionManager.class));
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_sku_pricing_version", Long.class);
        List<Runnable> saves = List.of(
                () -> coordinator.saveBuyerProducts(buyerCode, List.of(), 7L),
                () -> coordinator.saveBuyerPricing(buyerCode, List.of(product.getBuyerProductCode()),
                        GenericBatchPricingService.ProjectOverrides.allInherited(), 7L),
                () -> coordinator.saveProjectProducts(projectCodeA(), List.of(), 7L),
                () -> coordinator.saveProjectPricing(projectCodeA(), projectListingCodes,
                        GenericBatchPricingService.ProjectOverrides.allInherited(), 7L));
        for (boolean rollbackOnly : List.of(false, true)) {
            for (Runnable save : saves) {
                transaction.executeWithoutResult(status -> {
                    if (rollbackOnly) status.setRollbackOnly();
                    assertThatThrownBy(save::run)
                            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
                });
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_sku_pricing_version", Long.class)).isEqualTo(before);
        assertThat(listingMapper.selectById(listing.getId()).getStatus()).isEqualTo(BuyerListingStatus.DRAFT.getCode());
    }

    private void publishAuto() {
        refreshService.refresh(listing.getId(), BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, 7L);
    }

    private String projectCodeA() {
        return jdbc.queryForObject("""
                SELECT p.project_code FROM sc_project_listing pl
                JOIN sc_buyer_project p ON p.id = pl.project_id
                WHERE pl.buyer_product_id=? ORDER BY pl.id ASC LIMIT 1
                """, String.class, product.getId());
    }

    private void buildFixture() {
        for (String table : List.of("sc_std_routing_rule_scope", "sc_std_routing_candidate", "sc_std_routing_rule",
                "sc_supplier_routing_policy_version", "sc_supplier_routing_policy")) {
            jdbc.update("DELETE FROM " + table);
        }
        LocalDateTime now = context.getBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class).now();
        String suffix = Long.toString(++sequence);
        buyerCode = context.getBean(BuyerCommandService.class).createBuyer(
                new BuyerCommandService.CreateBuyerCommand(
                        null, "编排买家" + suffix, "编排买家" + suffix, null, null, "AIFULI_MALL",
                        "admin-" + suffix, now.minusMinutes(1), 1L));
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
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(skuCode, spuCode, "编排SKU" + suffix,
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
        SupplierDO supplier = supply("SP" + suffix);
        context.getBean(GenericBatchPricingService.class).initializePlatformDefaults(
                new PlatformSkuPricingInitializationEvent(List.of(skuCode), 1L));
        context.getBean(GenericBatchPricingService.class).initializeBuyerProduct(
                new BuyerProductPricingInitializationEvent(product.getId(), 1L));
        BuyerProjectDO projectA = project("PA" + suffix);
        BuyerProjectDO projectB = project("PB" + suffix);
        projectListingCodes = List.of(projectListing(projectA, now), projectListing(projectB, now));
        publishRules(List.of(new RoutingPolicyPublicationService.RuleDraft("RA" + suffix, "RA" + suffix,
                "FIXED", supplier.getSupplierCode(), "ALL", List.of(), "SPECIFIED",
                List.of(projectA.getProjectCode()), "SPECIFIED", List.of("EQUITY_PRODUCT"),
                "ALL", List.of(), null, "STABLE", "PRICE", "COMPOSITE", 10, (short) 10, false, List.of()),
                new RoutingPolicyPublicationService.RuleDraft("RB" + suffix, "RB" + suffix,
                        "FIXED", supplier.getSupplierCode(), "ALL", List.of(), "SPECIFIED",
                        List.of(projectB.getProjectCode()), "SPECIFIED", List.of("EQUITY_PRODUCT"),
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

    private String projectListing(BuyerProjectDO project, LocalDateTime now) {
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
        return listing.getListingCode();
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
                "ADMIN" + sequence, "编排策略", "V1", LocalDateTime.now().minusMinutes(1), null, rules, 1L, 40, 30, 30));
        publicationService.publish(new RoutingPolicyPublicationService.PublishCommand(draft.policyVersionId(), 0L,
                "编排夹具", 1L, "fixture", null, null));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            AesGcmEnvelopeCrypto.class, ProductCatalogCommandService.class, ProductCenterTestConfiguration.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.SettlementPriceResolver.class,
            com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService.class,
            com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductSelectionBatchTransactionalWorker.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogIdentityWriter.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogIdentityProbe.class,
            GenericBatchPricingService.class, OutboxEventRepository.class, BuyerCommandService.class,
            RoutingPolicyPublicationService.class, SupplierRoutingApplicationService.class,
            BuyerListingPublicationService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingCommandService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingPriceSource.class,
            com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties.class})
    @ComponentScan(basePackages = "com.aifuli.module.supplychain.tradecore.catalog.application",
            useDefaultFilters = false, includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = ".*(CurrentSupplyEligibilityReader|BuyerSelectionCatalogRefreshService"
                            + "|BuyerSelectionCatalogRefreshTransactionalWorker|BuyerSelectionCatalogAdminCoordinator)"))
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
