package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.*;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.*;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.*;
import com.aifuli.module.supplychain.tradecore.supply.application.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 专用MySQL验证真实生命周期、同步定价新视图、历史冻结与RC锁等待；不mock供给成本。
 * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#4-门禁与验证。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupplierOfferLifecycleIntegrationTest {
    final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("offer_lifecycle").withUsername("test").withPassword("test");
    ConfigurableApplicationContext context;
    JdbcTemplate jdbc;
    SupplierCooperativeProductService cooperative;
    SupplierOfferPublicationService publication;
    GenericBatchPricingService pricing;
    SupplierOfferEligibilityService eligibility;
    SupplierOfferMapper offers;
    LocalDateTime now;
    String offerNo;

    @BeforeAll void start() {
        mysql.start();
        context = new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled",true,
                        "aifuli.supply-chain.datasource.url",mysql.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username",mysql.getUsername(),
                        "aifuli.supply-chain.datasource.password",mysql.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name","com.mysql.cj.jdbc.Driver",
                        "aifuli.supply-chain.connector.allowed-adapter-types","QNM_GAME_ORDER_V1")).run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        cooperative = context.getBean(SupplierCooperativeProductService.class);
        publication = context.getBean(SupplierOfferPublicationService.class);
        pricing = context.getBean(GenericBatchPricingService.class);
        eligibility = context.getBean(SupplierOfferEligibilityService.class);
        offers = context.getBean(SupplierOfferMapper.class);
    }
    @AfterAll void stop() { if(context != null) context.close(); mysql.stop(); }
    @BeforeEach void seed() {
        for (String table : List.of("sc_project_sku_pricing_version", "sc_project_sku_pricing", "sc_project_listing",
                "sc_buyer_sku_pricing_version", "sc_buyer_sku_pricing", "sc_buyer_product", "sc_buyer_project", "sc_buyer",
                "sc_outbox_event", "sc_supplier_config_publish_record", "sc_supplier_external_sku_binding",
                "sc_supplier_offer_version", "sc_supplier_offer", "sc_inventory_source", "sc_supplier_connector_capability",
                "sc_supplier_connector_version", "sc_supplier_connector", "sc_credential_slot", "sc_supplier_supply_scope",
                "sc_supplier", "sc_platform_sku_pricing_version", "sc_platform_sku_pricing", "sc_platform_sku", "sc_platform_spu")) {
            jdbc.execute("DELETE FROM " + table);
        }
        now = context.getBean(SupplyChainDatabaseClock.class).now();
        context.getBean(PlatformSpuMapper.class).insert(PlatformSpuDO.builder().id(1L).spuCode("SPU_1").spuName("商品")
                .brandId(1L).primaryCategoryId(1L).sellableCategory("EQUITY").productType("EQUITY_PRODUCT").productClassification("VIRTUAL_CARD")
                .status((short)10).version(0L).createdAt(now).updatedAt(now).build());
        context.getBean(PlatformSkuMapper.class).insert(PlatformSkuDO.builder().id(1L).skuCode("SKU_1").spuId(1L).skuName("规格")
                .fulfillmentType("VOUCHER_CODE").faceValue(10000L).currency("CNY").resultSchemaCode("VOUCHER_CODE_V1").resultSchemaVersion(1)
                .marketPrice(10000L).suggestedSalePrice(10000L).suggestedSalePriceRuleJson(com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.equalMarketPrice()).taxIncluded(false).status((short)10).version(0L).createdAt(now).updatedAt(now).build());
        context.getBean(SupplierMapper.class).insert(SupplierDO.builder().id(1L).supplierCode("SUP_1").supplierName("供应商").shortName("供应商")
                .supplierType("STANDARD_SUPPLIER").status((short)10).version(0L).createdAt(now).updatedAt(now).build());
        context.getBean(SupplierSupplyScopeMapper.class).insert(SupplierSupplyScopeDO.builder().id(1L).supplierId(1L)
                .sellableCategory("EQUITY").productType("EQUITY_PRODUCT").productClassification("VIRTUAL_CARD")
                .maxQuantity(100L).minQuantity(1L).version(0L).createdAt(now).updatedAt(now).build());
        offerNo = cooperative.createPublished("SUP_1",List.of(line(9500L)),1L).getFirst();
        var direct = new FormulaRule(PriceMode.DIRECT_MARKET_PRICE,null,null,null,null);
        pricing.savePlatform(List.of("SKU_1"),new PricingConfig(direct,direct,
                new ServiceFeeRule(BigDecimal.ONE,FixedOperator.MULTIPLY,BigDecimal.ZERO,true,null),
                new HideRule(null,null),new TaxMetadata(false,PricingTaxRateCodes.NON_TAXABLE,null)),1L);
    }
    SupplierCooperativeProductService.DraftLine line(long price) {
        return new SupplierCooperativeProductService.DraftLine("SKU_1","FIXED",price,null,null,"API_DIRECT",100L,1L,"EXT_1");
    }
    SupplierOfferPublicationService.ListOnShelfCommand command() {
        return new SupplierOfferPublicationService.ListOnShelfCommand("SUP_1",offerNo,null,1L,"trace","corr","cause");
    }
    SupplierOfferDO offer() { return offers.selectByNo(offerNo); }
    Long cost() { return eligibility.resolveMinimumSupplyPrices(List.of(1L),context.getBean(SupplyChainDatabaseClock.class).now()).get(1L); }
    void connector(int versionNo) {
        if (versionNo == 1) {
            context.getBean(CredentialSlotMapper.class).insert(CredentialSlotDO.builder().id(1L).slotNo("CRD_1").providerType("ENV")
                    .secretRef("env:TEST").maskedHint("test").activeSecretVersion("v1").status((short)10).version(0L).createdAt(now).updatedAt(now).build());
            context.getBean(SupplierConnectorMapper.class).insert(SupplierConnectorDO.builder().id(1L).connectorNo("CON_1")
                    .supplierId(1L).connectorCode("PRIMARY").adapterType("QNM_GAME_ORDER_V1").status((short)10).version(0L).createdAt(now).updatedAt(now).build());
        }
        context.getBean(SupplierConnectorVersionMapper.class).insert(SupplierConnectorVersionDO.builder().id((long)versionNo).connectorId(1L)
                .versionNo(versionNo).baseUrl("https://fixture.invalid").merchantAccount("test").credentialSlotId(1L).protocolVersion("V1")
                .callbackPath("/callback").connectTimeoutMs(1000).readTimeoutMs(1000).rateLimit(10).concurrencyLimit(2)
                .adapterConfigSchemaVersion(1).adapterConfigJson("{}").validationStatus((short)10).configDigest(new byte[32])
                .status((short)10).version(0L).createdAt(now).updatedAt(now).build());
        for(String code:List.of("ISSUE_ASSET","QUERY_ORDER","RECEIVE_CALLBACK")) {
            context.getBean(SupplierConnectorCapabilityMapper.class).insert(SupplierConnectorCapabilityDO.builder()
                    .connectorVersionId((long)versionNo).capabilityCode(code).enabled((short)1).metadataVersion(1).createdAt(now).build());
        }
        jdbc.update("UPDATE sc_supplier_connector SET current_version_id=?,status=10,version=version+1 WHERE id=1",versionNo);
    }

    @Test void completeLifecycleRetainsHistoryAndUsesOnlyPublishedPrice() {
        assertThat(offer().getStatus()).isEqualTo(OfferStatus.SUSPENDED.getCode());
        assertThat(cost()).isNull();
        assertThatThrownBy(() -> cooperative.changeOfferStatus("SUP_1",offerNo,OfferStatus.PUBLISHED,1L)).isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
        assertThat(offer().getCurrentVersionId()).isNull();
        connector(1);
        cooperative.changeOfferStatus("SUP_1",offerNo,OfferStatus.PUBLISHED,1L);
        Long first = offer().getCurrentVersionId();
        assertThat(cost()).isEqualTo(9500L);
        Long firstPricing = jdbc.queryForObject("SELECT current_version_id FROM sc_platform_sku_pricing", Long.class);
        Long priceVersions = jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class);
        cooperative.updateCommercial("SUP_1",offerNo,line(9600L),1L);
        assertThat(cost()).isEqualTo(9500L);
        assertThat(offer().getCurrentVersionId()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM sc_platform_sku_pricing", Long.class)).isEqualTo(firstPricing);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class)).isEqualTo(priceVersions);
        publication.listOnShelf(command());
        assertThat(cost()).isEqualTo(9600L);
        assertThat(jdbc.queryForObject("SELECT supplier_settlement_unit_price FROM sc_supplier_offer_version WHERE id=?",Long.class,first)).isEqualTo(9500L);
        Long published = offer().getCurrentVersionId();
        Long binding = jdbc.queryForObject("SELECT offer_version_id FROM sc_supplier_external_sku_binding WHERE offer_id=?",Long.class,offer().getId());
        cooperative.changeOfferStatus("SUP_1",offerNo,OfferStatus.SUSPENDED,1L);
        assertThat(cost()).isNull();
        assertThat(offer().getCurrentVersionId()).isEqualTo(published);
        assertThat(jdbc.queryForObject("SELECT offer_version_id FROM sc_supplier_external_sku_binding WHERE offer_id=?",Long.class,offer().getId())).isEqualTo(binding);
        publication.listOnShelf(command());
        assertThat(cost()).isEqualTo(9600L);
        assertThat(jdbc.queryForObject("SELECT unavailable_reason FROM sc_platform_sku_pricing WHERE platform_sku_id=1",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_inventory_source",Long.class)).isEqualTo(1L);
    }
    @Test void scopeTypeEditRetiresPublishedOfferAndInvalidatesCostWithoutChangingFrozenVersion() {
        connector(1); publication.listOnShelf(command());
        var frozen = jdbc.queryForList("SELECT * FROM sc_supplier_offer_version WHERE offer_id=?", offer().getId());
        Long version = offer().getCurrentVersionId();
        Long pricingBefore = jdbc.queryForObject("SELECT current_version_id FROM sc_platform_sku_pricing WHERE platform_sku_id=1", Long.class);
        assertThat(cost()).isEqualTo(9500L);
        editScopeType();
        assertThat(offer().getStatus()).isEqualTo(OfferStatus.RETIRED.getCode());
        assertThat(offer().getCurrentVersionId()).isEqualTo(version);
        assertThat(cost()).isNull();
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM sc_platform_sku_pricing WHERE platform_sku_id=1", Long.class)).isNotEqualTo(pricingBefore);
        assertThat(jdbc.queryForObject("SELECT v.supply_price_cents FROM sc_platform_sku_pricing p JOIN sc_platform_sku_pricing_version v ON p.current_version_id=v.id WHERE p.platform_sku_id=1", Long.class)).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM sc_supplier_offer_version WHERE offer_id=?", offer().getId())).isEqualTo(frozen);
    }

    @Test void scopeSaveRollsBackConfigurationOffersAndPricingWhenSynchronousRecalculationFails() {
        connector(1); publication.listOnShelf(command());
        var before = jdbc.queryForList("SELECT * FROM sc_supplier_offer");
        var scopeBefore = jdbc.queryForList("SELECT * FROM sc_supplier_supply_scope");
        var pricingBefore = jdbc.queryForList("SELECT * FROM sc_platform_sku_pricing");
        Long versionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class);
        failScopePricing = true;
        try {
            assertThatThrownBy(this::editScopeType).isInstanceOf(IllegalStateException.class).hasMessageContaining("fixture pricing failure");
        } finally { failScopePricing = false; }
        assertThat(jdbc.queryForList("SELECT * FROM sc_supplier_offer")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM sc_supplier_supply_scope")).isEqualTo(scopeBefore);
        assertThat(jdbc.queryForList("SELECT * FROM sc_platform_sku_pricing")).isEqualTo(pricingBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class)).isEqualTo(versionsBefore);
    }

    @Test void failingScopeInsertAfterRetirementRestoresDeletedModesAndPricingHistory() {
        connector(1); publication.listOnShelf(command());
        context.getBean(SupplierSupplyScopeService.class).replaceAll("SUP_1", List.of(new SupplierSupplyScopeService.ScopeLine(
                1L, "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", List.of("API_DIRECT"), 100L, 1L)), 1L);
        var before = jdbc.queryForList("SELECT * FROM sc_supplier_offer");
        var scopesBefore = jdbc.queryForList("SELECT * FROM sc_supplier_supply_scope");
        var modesBefore = jdbc.queryForList("SELECT * FROM sc_supplier_supply_scope_mode WHERE supply_scope_id=1");
        var pricesBefore = jdbc.queryForList("SELECT * FROM sc_platform_sku_pricing");
        Long versionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class);
        // 仅独占测试库注入约束，模拟配置重建后段失败；不需要SUPER权限、不更改生产DDL。
        jdbc.execute("ALTER TABLE sc_supplier_supply_scope ADD CONSTRAINT fixture_reject_scope CHECK (product_type<>'MOVIE')");
        try {
            assertThatThrownBy(this::editScopeType).hasStackTraceContaining("fixture_reject_scope");
        } finally { jdbc.execute("ALTER TABLE sc_supplier_supply_scope DROP CHECK fixture_reject_scope"); }
        assertThat(jdbc.queryForList("SELECT * FROM sc_supplier_offer")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM sc_supplier_supply_scope")).isEqualTo(scopesBefore);
        assertThat(jdbc.queryForList("SELECT * FROM sc_supplier_supply_scope_mode WHERE supply_scope_id=1")).isEqualTo(modesBefore);
        assertThat(jdbc.queryForList("SELECT * FROM sc_platform_sku_pricing")).isEqualTo(pricesBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class)).isEqualTo(versionsBefore);
    }

    @Test void scopeSaveRejectsActualRepeatableReadBeforeWriting() {
        transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ).executeWithoutResult(status -> {
            assertThatThrownBy(this::editScopeType).isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_COMMITTED");
            status.setRollbackOnly();
        });
        assertThat(offer().getStatus()).isEqualTo(OfferStatus.SUSPENDED.getCode());
    }

    @Test void concurrentCreateWaitsForScopeEditAndCannotCreateAgainstOldType() throws Exception {
        cooperative.retire("SUP_1", offerNo, 1L);
        var edited = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var saving = threads.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
                editScopeType(); edited.countDown(); await(release);
            }));
            assertThat(edited.await(10, TimeUnit.SECONDS)).isTrue();
            var creating = threads.submit(() -> {
                attempting.countDown();
                return catchThrowable(() -> cooperative.createPublished("SUP_1", List.of(line(9500L)), 1L));
            });
            assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> creating.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); saving.get(10, TimeUnit.SECONDS);
            assertThat(creating.get(10, TimeUnit.SECONDS)).isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_offer WHERE status<>?", Long.class, OfferStatus.RETIRED.getCode())).isZero();
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    void editScopeType() {
        context.getBean(SupplierSupplyScopeService.class).replaceAll("SUP_1", List.of(new SupplierSupplyScopeService.ScopeLine(
                1L, "EQUITY", "MOVIE", "VIRTUAL_CARD", List.of("API_DIRECT"), 100L, 1L)), 1L);
    }

    private static volatile boolean failScopePricing;

    @Test void invalidValidityWindowRollsBackInventoryAndVersionPublication() {
        connector(1);
        assertThatThrownBy(() -> publication.listOnShelf(new SupplierOfferPublicationService.ListOnShelfCommand(
                "SUP_1",offerNo,now.minusDays(1),1L,"trace","corr","cause"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(offer().getStatus()).isEqualTo(OfferStatus.SUSPENDED.getCode());
        assertThat(offer().getCurrentVersionId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_inventory_source",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_offer_version",Long.class)).isZero();
    }
    @Test void connectorUpgradeRequiresPublishingNewOfferAndRecalculationSeesNewVersion() {
        connector(1); publication.listOnShelf(command());
        Long first = offer().getCurrentVersionId(); connector(2);
        assertThat(cost()).isNull();
        publication.listOnShelf(command());
        assertThat(cost()).isEqualTo(9500L);
        assertThat(jdbc.queryForObject("SELECT connector_version_id FROM sc_supplier_offer_version WHERE id=?",Long.class,first)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT v.supply_price_cents FROM sc_platform_sku_pricing p JOIN sc_platform_sku_pricing_version v ON p.current_version_id=v.id WHERE p.platform_sku_id=1",Long.class)).isEqualTo(9500L);
    }
    @Test void actualOuterRepeatableReadIsRejectedBeforeAnyLifecycleWrite() {
        connector(1);
        var tx = transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT version FROM sc_supplier_offer WHERE offer_no=?",Long.class,offerNo);
            assertThatThrownBy(() -> publication.listOnShelf(command())).isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_COMMITTED");
            status.setRollbackOnly();
        });
        assertThat(offer().getCurrentVersionId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_inventory_source",Long.class)).isZero();
    }
    @Test void waitingReadCommittedPublisherObservesCommittedConnectorRetirement() throws Exception {
        connector(1); publication.listOnShelf(command());
        Long oldVersion = offer().getCurrentVersionId();
        var locked = new CountDownLatch(1); var preRead = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> retire = pool.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
                context.getBean(SupplierMapper.class).selectByIdForUpdate(1L); locked.countDown();
                try { if(!preRead.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("pre-read timeout"); } catch(InterruptedException e){ throw new IllegalStateException(e); }
                context.getBean(ConnectorPublicationService.class).retire(new ConnectorPublicationService.RetireConnectorVersionCommand("CON_1",1L,1L,"测试退役","trace","corr","cause"));
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); } catch(InterruptedException e){ throw new IllegalStateException(e); }
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            Future<Throwable> publish = pool.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
                jdbc.queryForObject("SELECT current_version_id FROM sc_supplier_connector WHERE id=1",Long.class); preRead.countDown();
                Throwable failure = catchThrowable(() -> publication.listOnShelf(command()));
                status.setRollbackOnly();
                return failure;
            }));
            assertThat(preRead.await(10,TimeUnit.SECONDS)).isTrue(); release.countDown();
            retire.get(20,TimeUnit.SECONDS);
            assertThat(publish.get(20,TimeUnit.SECONDS)).isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
            assertThat(offer().getCurrentVersionId()).isEqualTo(oldVersion);
            assertThat(offer().getStatus()).isEqualTo(OfferStatus.PUBLISHED.getCode());
            assertThat(cost()).isNull();
        } finally { release.countDown(); preRead.countDown(); pool.shutdownNow(); }
    }
    private static volatile CountDownLatch reverseBatchReachedFirstRoot;
    private static volatile CountDownLatch independentInitializerReachedRoot;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void independentInitializationReloadsSkuPricesAfterWaitingRoot(boolean buyerLevel) throws Exception {
        connector(1); publication.listOnShelf(command()); seedBuyerAndProjectPricing();
        var priceChanged = new CountDownLatch(1); var initializerWaiting = new CountDownLatch(1);
        independentInitializerReachedRoot = initializerWaiting;
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> change = pool.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
                context.getBean(PlatformSkuPricingMapper.class).selectBySkuIdForUpdate(1L);
                jdbc.update("UPDATE sc_platform_sku SET market_price=20000,suggested_sale_price=20000 WHERE id=1");
                pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L));
                priceChanged.countDown(); await(initializerWaiting);
            }));
            assertThat(priceChanged.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> initialize = pool.submit(() -> {
                Thread.currentThread().setName("independent-buyer-refresh");
                if (buyerLevel) pricing.initializeBuyerProduct(new BuyerProductPricingInitializationEvent(1L, 1L));
                else pricing.initializeProjectListing(new ProjectListingPricingInitializationEvent(1L, 1L));
            });
            change.get(20, TimeUnit.SECONDS); initialize.get(20, TimeUnit.SECONDS);
            String identity = buyerLevel ? "sc_buyer_sku_pricing" : "sc_project_sku_pricing";
            String version = identity + "_version";
            Map<String, Object> current = jdbc.queryForMap("SELECT market_price_cents,suggested_sale_price_cents FROM " + version + " WHERE id=(SELECT current_version_id FROM " + identity + ")");
            assertThat(((Number) current.get("market_price_cents")).longValue()).isEqualTo(20000L);
            assertThat(((Number) current.get("suggested_sale_price_cents")).longValue()).isEqualTo(20000L);
        } finally { initializerWaiting.countDown(); independentInitializerReachedRoot = null; pool.shutdownNow(); }
    }

    @Test void independentBuyerRefreshWaitsForLifecycleAndUsesCommittedParent() throws Exception {
        connector(1); publication.listOnShelf(command()); seedBuyerAndProjectPricing();
        var downCompleted = new CountDownLatch(1); var initializerWaiting = new CountDownLatch(1);
        independentInitializerReachedRoot = initializerWaiting;
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> down = pool.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
                cooperative.changeOfferStatus("SUP_1", offerNo, OfferStatus.SUSPENDED, 1L);
                downCompleted.countDown();
                await(initializerWaiting);
            }));
            assertThat(downCompleted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> refresh = pool.submit(() -> {
                Thread.currentThread().setName("independent-buyer-refresh");
                pricing.initializeBuyerProduct(new BuyerProductPricingInitializationEvent(1L, 1L));
            });
            down.get(20, TimeUnit.SECONDS); refresh.get(20, TimeUnit.SECONDS);
            Long platformVersion = jdbc.queryForObject("SELECT current_version_id FROM sc_platform_sku_pricing", Long.class);
            assertThat(jdbc.queryForObject("SELECT platform_pricing_version_id FROM sc_buyer_sku_pricing_version WHERE id=(SELECT current_version_id FROM sc_buyer_sku_pricing)", Long.class))
                    .isEqualTo(platformVersion);
            assertThat(jdbc.queryForObject("SELECT supply_price_cents FROM sc_buyer_sku_pricing_version WHERE id=(SELECT current_version_id FROM sc_buyer_sku_pricing)", Long.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT pricing_status FROM sc_buyer_sku_pricing", String.class)).isEqualTo("DEPENDENCY_MISSING");
        } finally { initializerWaiting.countDown(); independentInitializerReachedRoot = null; pool.shutdownNow(); }
    }

    @Test void reversePlatformBatchAndAscendingLifecycleRecalculationDoNotDeadlock() throws Exception {
        var sku = context.getBean(PlatformSkuMapper.class).selectById(1L);
        sku.setId(2L); sku.setSkuCode("SKU_2"); context.getBean(PlatformSkuMapper.class).insert(sku);
        pricing.savePlatform(List.of("SKU_2"), config(), 1L);
        var held = new CountDownLatch(1); var reached = new CountDownLatch(1);
        reverseBatchReachedFirstRoot = reached;
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> ascending = pool.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
                context.getBean(PlatformSkuPricingMapper.class).selectBySkuIdForUpdate(1L);
                held.countDown();
                await(reached);
                pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L));
                pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(2L));
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> reverse = pool.submit(() -> {
                Thread.currentThread().setName("reverse-price-batch");
                pricing.savePlatform(List.of("SKU_2", "SKU_1"), config(), 1L);
            });
            ascending.get(20, TimeUnit.SECONDS); reverse.get(20, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class)).isEqualTo(6L);
        } finally { reached.countDown(); reverseBatchReachedFirstRoot = null; pool.shutdownNow(); }
    }

    @Test void restoresAllThreePricingLevelsAndRollsBackPublicationAfterChildFailure() {
        connector(1); publication.listOnShelf(command());
        seedBuyerAndProjectPricing();
        cooperative.changeOfferStatus("SUP_1", offerNo, OfferStatus.SUSPENDED, 1L);
        for (String table : List.of("sc_platform_sku_pricing", "sc_buyer_sku_pricing", "sc_project_sku_pricing")) {
            assertThat(jdbc.queryForObject("SELECT pricing_status FROM " + table, String.class)).isEqualTo("DEPENDENCY_MISSING");
        }
        publication.listOnShelf(command());
        for (String table : List.of("sc_platform_sku_pricing", "sc_buyer_sku_pricing", "sc_project_sku_pricing")) {
            assertThat(jdbc.queryForObject("SELECT pricing_status FROM " + table, String.class)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT unavailable_reason FROM " + table, String.class)).isNull();
        }
        cooperative.updateCommercial("SUP_1", offerNo, line(9700L), 1L);
        Long offerVersion = offer().getCurrentVersionId();
        Map<String, Long> versions = new HashMap<>();
        for (String table : List.of("sc_platform_sku_pricing", "sc_buyer_sku_pricing", "sc_project_sku_pricing")) {
            versions.put(table, jdbc.queryForObject("SELECT current_version_id FROM " + table, Long.class));
        }
        Long offerCount = jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_offer_version", Long.class);
        Long priceCount = jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class);
        // 合法JSON但非法覆盖结构：失败发生在平台及分销新版本写入之后，验证整次发布回滚。
        jdbc.update("UPDATE sc_project_sku_pricing_version SET override_json='\"broken\"' WHERE id=?", versions.get("sc_project_sku_pricing"));
        assertThatThrownBy(() -> publication.listOnShelf(command())).isInstanceOf(RuntimeException.class);
        assertThat(offer().getCurrentVersionId()).isEqualTo(offerVersion);
        assertThat(cost()).isEqualTo(9500L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_offer_version", Long.class)).isEqualTo(offerCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class)).isEqualTo(priceCount);
        versions.forEach((table, version) -> assertThat(jdbc.queryForObject("SELECT current_version_id FROM " + table, Long.class)).isEqualTo(version));
    }

    private void seedBuyerAndProjectPricing() {
        jdbc.update("INSERT INTO sc_buyer(id,buyer_code,buyer_name,short_name,buyer_type,status,version,created_at,updated_at) VALUES(1,'BUY_1','分销商','分销商','SAAS_MALL',10,0,NOW(3),NOW(3))");
        jdbc.update("INSERT INTO sc_buyer_project(id,project_code,project_name,buyer_id,catalog_mode,status,version,created_at,updated_at) VALUES(1,'PRJ_1','项目',1,'MANAGED',10,0,NOW(3),NOW(3))");
        jdbc.update("INSERT INTO sc_buyer_product(id,buyer_product_code,buyer_id,platform_sku_id,status,version,created_at,updated_at) VALUES(1,'BP_1',1,1,10,0,NOW(3),NOW(3))");
        pricing.saveBuyer("BUY_1", List.of("BP_1"), GenericBatchPricingService.ProjectOverrides.allInherited(), 1L);
        jdbc.update("INSERT INTO sc_project_listing(id,listing_code,project_id,buyer_product_id,status,version,created_at,updated_at) VALUES(1,'PL_1',1,1,10,0,NOW(3),NOW(3))");
        pricing.saveProject("PRJ_1", List.of("PL_1"), GenericBatchPricingService.ProjectOverrides.allInherited(), 1L);
    }
    private PricingConfig config() {
        var direct = new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null);
        return new PricingConfig(direct, direct, new ServiceFeeRule(BigDecimal.ONE, FixedOperator.MULTIPLY, BigDecimal.ZERO, true, null),
                new HideRule(null, null), new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, null));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    TransactionTemplate transaction(int isolation) {
        var tx = new TransactionTemplate(context.getBean("supplyChainTransactionManager",PlatformTransactionManager.class));
        tx.setIsolationLevel(isolation); return tx;
    }
    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxEventRepository.class, ProductCenterTestConfiguration.class, ClosedJsonSchemaValidator.class,
            SupplierCooperativeProductService.class, SupplierSupplyScopeService.class, SupplierOfferPublicationService.class, GenericBatchPricingService.class,
            ConnectorPublicationService.class, ConnectorCredentialBindingValidator.class,
            com.aifuli.module.supplychain.infrastructure.security.AdapterTypeAllowList.class,
            com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata.class,
            com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry.class})
    static class TestConfiguration {
        /** 仅观察真实Mapper进入根锁的时刻，不替换SQL、价格或事务。 */
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor pricingLockProbe() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof PlatformSkuPricingMapper)) return bean;
                    return java.lang.reflect.Proxy.newProxyInstance(bean.getClass().getClassLoader(), new Class[]{PlatformSkuPricingMapper.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("selectBySkuIdForUpdate") && Long.valueOf(1L).equals(args[0])
                                        && Thread.currentThread().getName().equals("reverse-price-batch") && reverseBatchReachedFirstRoot != null) {
                                    reverseBatchReachedFirstRoot.countDown();
                                }
                                if (method.getName().equals("selectBySkuIdForUpdate") && Long.valueOf(1L).equals(args[0])
                                        && Thread.currentThread().getName().equals("independent-buyer-refresh") && independentInitializerReachedRoot != null) {
                                    independentInitializerReachedRoot.countDown();
                                }
                                try { return method.invoke(bean, args); }
                                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                            });
                }
            };
        }
        @Bean ScopePricingFailure scopePricingFailure() { return new ScopePricingFailure(); }
        static class ScopePricingFailure {
            @org.springframework.context.event.EventListener
            @org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
            public void onPricing(PricingDependencyChangedEvent event) {
                if (failScopePricing) throw new IllegalStateException("fixture pricing failure");
            }
        }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean TaxRateDictionaryAdapter dictionary() {
            TaxRateDictionaryAdapter dictionary = mock(TaxRateDictionaryAdapter.class);
            when(dictionary.requireEnabled(any(),anyString())).thenAnswer(call -> new TaxMetadata(call.getArgument(0),call.getArgument(1),null));
            return dictionary;
        }
    }
}
