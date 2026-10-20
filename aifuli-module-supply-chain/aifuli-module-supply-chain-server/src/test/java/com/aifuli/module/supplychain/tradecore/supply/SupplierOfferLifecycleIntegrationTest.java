package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.*;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.*;
import com.aifuli.module.supplychain.infrastructure.ledger.DefaultAppendScheduleIdentityLedgerService;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
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
                .properties(Map.of("aifuli.supply-chain.enabled",true, "aifuli.supply-chain.timer.enabled",false,
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
        context.getBean(TimerEngineLifecycle.class).stop();
        for (String table : List.of("sc_timer_execution", "sc_timer_task", "sc_timer_schedule_identity",
                "sc_sku_suggested_price_state", "sc_standard_pricing_quote", "sc_project_sku_pricing_version", "sc_project_sku_pricing", "sc_project_listing",
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
                .fulfillmentType("VOUCHER_CODE").faceValue(10000L).currency("CNY")
                .targetSchemaCode("VOUCHER_DELIVERY_TARGET").targetSchemaVersion(1).resultSchemaCode("VOUCHER_CODE_RESULT").resultSchemaVersion(1)
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
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class)).isEqualTo(4L);
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

    /** 建议价仅在独立状态表物化；测试用运营写入口同事务语义保存公式，不改供应资格。 */
    private void supplyRule(String percentage, SuggestedSalePriceRule.Operator operator, String amount) {
        transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(tx -> {
            var sku = context.getBean(PlatformSkuMapper.class).selectByIdForUpdate(1L);
            sku.setSuggestedSalePriceRuleJson(new SuggestedSalePriceRule(SuggestedSalePriceRule.Mode.CUSTOM,
                    SuggestedSalePriceRule.Base.SUPPLY_PRICE, new BigDecimal(percentage), operator,
                    new BigDecimal(amount), null, null));
            context.getBean(PlatformSkuMapper.class).updateById(sku);
            pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L));
        });
    }

    private Map<String,Object> suggestedState() {
        return jdbc.queryForMap("SELECT * FROM sc_sku_suggested_price_state WHERE platform_sku_id=1");
    }
    private PricingConfig suggestedConfig() {
        var formula = new FormulaRule(PriceMode.CUSTOM, PriceBase.SUGGESTED_SALE_PRICE,
                new BigDecimal("100"), FixedOperator.ADD, BigDecimal.ZERO);
        return new PricingConfig(formula, formula, config().serviceFeeRule(), config().hideRule(), config().taxMetadata());
    }
    private void assertSuggested(String status, Long amount) {
        var state = suggestedState();
        assertThat(state.get("calculation_status")).isEqualTo(status);
        assertThat(state.get("effective_price_cents")).isEqualTo(amount);
        var resolved = context.getBean(SuggestedSalePriceResolver.class).resolve(
                context.getBean(PlatformSkuMapper.class).selectById(1L), context.getBean(SupplyChainDatabaseClock.class).now());
        assertThat(resolved.amountCents()).isEqualTo(amount);
        assertThat(resolved.status().name()).isEqualTo(status);
    }

    @Test void supplySuggestedPricePendingPublishDraftNoopInvalidateAndRecoverAllThreeLevels() {
        supplyRule("110", SuggestedSalePriceRule.Operator.ADD, "1");
        assertSuggested("DEPENDENCY_MISSING", null);
        assertThat(jdbc.queryForObject("SELECT suggested_sale_price FROM sc_platform_sku WHERE id=1", Long.class)).isEqualTo(10000L);
        connector(1);
        cooperative.updateCommercial("SUP_1", offerNo, line(8000L), 1L);
        publication.listOnShelf(command());
        assertSuggested("READY", 8900L);
        pricing.savePlatform(List.of("SKU_1"), suggestedConfig(), 1L);
        seedBuyerAndProjectPricing();
        cooperative.updateCommercial("SUP_1", offerNo, line(9000L), 1L);
        assertSuggested("READY", 8900L);
        publication.listOnShelf(command());
        assertSuggested("READY", 10000L);
        for (String table : List.of("sc_platform_sku_pricing_version", "sc_buyer_sku_pricing_version", "sc_project_sku_pricing_version")) {
            String root = table.substring(0, table.length() - "_version".length());
            assertThat(jdbc.queryForObject("SELECT suggested_sale_price_cents FROM " + table + " WHERE id=(SELECT current_version_id FROM " + root + ")", Long.class)).isEqualTo(10000L);
            assertThat(jdbc.queryForObject("SELECT sales_price_cents FROM " + table + " WHERE id=(SELECT current_version_id FROM " + root + ")", Long.class)).isEqualTo(10000L);
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class);
        Long revision = ((Number)suggestedState().get("revision")).longValue();
        publication.listOnShelf(command());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version", Long.class)).isEqualTo(count);
        assertThat(((Number)suggestedState().get("revision")).longValue()).isEqualTo(revision);
        cooperative.changeOfferStatus("SUP_1", offerNo, OfferStatus.SUSPENDED, 1L);
        assertSuggested("DEPENDENCY_MISSING", null);
        assertThat(suggestedState().get("last_valid_price_cents")).isEqualTo(10000L);
        for (String table : List.of("sc_platform_sku_pricing", "sc_buyer_sku_pricing", "sc_project_sku_pricing")) {
            assertThat(jdbc.queryForObject("SELECT pricing_status FROM " + table, String.class)).isEqualTo("DEPENDENCY_MISSING");
            assertThat(jdbc.queryForObject("SELECT suggested_sale_price_cents FROM " + table + "_version WHERE id=(SELECT current_version_id FROM " + table + ")", Long.class)).isNull();
        }
        publication.listOnShelf(command());
        assertSuggested("READY", 10000L);
        assertThat(suggestedState().get("unavailable_reason")).isNull();
        for (String table : List.of("sc_platform_sku_pricing", "sc_buyer_sku_pricing", "sc_project_sku_pricing")) {
            assertThat(jdbc.queryForObject("SELECT unavailable_reason FROM " + table, String.class)).isNull();
        }
    }

    @Test void changedSupplyCausingNegativeResultIsFailureAndDoesNotBlockDownOrRecovery() {
        connector(1); cooperative.updateCommercial("SUP_1", offerNo, line(8000L), 1L); publication.listOnShelf(command());
        supplyRule("100", SuggestedSalePriceRule.Operator.SUBTRACT, "50");
        assertSuggested("READY", 3000L);
        pricing.savePlatform(List.of("SKU_1"), suggestedConfig(), 1L);
        cooperative.updateCommercial("SUP_1", offerNo, line(4000L), 1L); publication.listOnShelf(command());
        assertSuggested("CALCULATION_FAILED", null);
        assertThat(suggestedState().get("last_valid_price_cents")).isEqualTo(3000L);
        cooperative.changeOfferStatus("SUP_1", offerNo, OfferStatus.SUSPENDED, 1L);
        assertSuggested("DEPENDENCY_MISSING", null);
        cooperative.updateCommercial("SUP_1", offerNo, line(9000L), 1L); publication.listOnShelf(command());
        assertSuggested("READY", 4000L);
    }

    @Test void delayedBoundaryReadsArePureAndFirstQuoteRefreshesAllLevelsButFrozenQuoteReplays() throws Exception {
        connector(1); publication.listOnShelf(command());
        supplyRule("110", SuggestedSalePriceRule.Operator.ADD, "1");
        pricing.savePlatform(List.of("SKU_1"), suggestedConfig(), 1L); seedBuyerAndProjectPricing();
        var quotes = context.getBean(StandardPricingQuoteService.class);
        var frozen = quotes.freezeForProject(new ProjectQuoteKeyContext(1L,1L,1L,"OLD",1));
        Long oldAmount = frozen.salesUnitPriceCents();
        // 真实版本不可变；构造已过期测试事实以确定性模拟调度延迟。
        jdbc.update("UPDATE sc_supplier_offer_version SET valid_from=NOW(3)-INTERVAL 2 SECOND,valid_to=NOW(3)-INTERVAL 1 SECOND WHERE id=?", offer().getCurrentVersionId());
        var before = suggestedState();
        var resolved = context.getBean(SuggestedSalePriceResolver.class).resolve(
                context.getBean(PlatformSkuMapper.class).selectById(1L),context.getBean(SupplyChainDatabaseClock.class).now());
        assertThat(resolved.amountCents()).isNull();
        assertThat(suggestedState()).isEqualTo(before);
        assertThatThrownBy(() -> quotes.freezeForProject(new ProjectQuoteKeyContext(1L,1L,1L,"NEW",1)))
                .isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_standard_pricing_quote WHERE buyer_order_no='NEW'",Long.class)).isZero();
        assertThat(quotes.freezeForProject(new ProjectQuoteKeyContext(1L,1L,1L,"OLD",1)).salesUnitPriceCents()).isEqualTo(oldAmount);
        // 新报价拒绝会回滚自身补偿；Timer独立事务最终推进不可售状态。
        context.getBean(SuggestedPriceBoundaryTimerHandler.class).handle(null,new ObjectMapper().createObjectNode().put("platformSkuId",1L));
        assertSuggested("DEPENDENCY_MISSING",null);
    }

    @Test void persistedBoundarySurvivesEngineStopAndDuplicateSchedulesThenRecoversAfterRestart() throws Exception {
        connector(1); supplyRule("110", SuggestedSalePriceRule.Operator.ADD, "1");
        LocalDateTime expireAt = context.getBean(SupplyChainDatabaseClock.class).now().plusSeconds(2);
        publication.listOnShelf(new SupplierOfferPublicationService.ListOnShelfCommand("SUP_1",offerNo,expireAt,1L,"trace","corr","cause"));
        assertSuggested("READY",10550L);
        pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type=?",Long.class,SuggestedPriceBoundaryTimerHandler.TASK_TYPE)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_schedule_identity WHERE task_type=?",Long.class,SuggestedPriceBoundaryTimerHandler.TASK_TYPE)).isEqualTo(1L);
        var props = context.getBean(TimerEngineProperties.class); props.setEnabled(true); props.setScanInterval(java.time.Duration.ofMillis(50));
        var engine = context.getBean(TimerEngineLifecycle.class); engine.start(); engine.stop();
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while (context.getBean(SupplyChainDatabaseClock.class).now().isBefore(expireAt) && System.nanoTime()<deadline) Thread.sleep(40);
        assertSuggestedStateOnly("READY",10550L);
        engine.start();
        try {
            deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (!"DEPENDENCY_MISSING".equals(suggestedState().get("calculation_status")) && System.nanoTime()<deadline) Thread.sleep(40);
            assertSuggested("DEPENDENCY_MISSING",null);
            deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (jdbc.queryForObject("SELECT status FROM sc_timer_task",Integer.class)!=com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper.STATUS_COMPLETED && System.nanoTime()<deadline) Thread.sleep(40);
            assertThat(jdbc.queryForObject("SELECT status FROM sc_timer_task",Integer.class)).isEqualTo((int)com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper.STATUS_COMPLETED);
        } finally { engine.stop(); }
    }
    private void assertSuggestedStateOnly(String status,Long amount) {
        assertThat(suggestedState().get("calculation_status")).isEqualTo(status);
        assertThat(suggestedState().get("effective_price_cents")).isEqualTo(amount);
    }

    @Test void waitingSupplyChangeReReadsNewRuleWithoutOverwritingSkuAndRollsBackStateWithChildFailure() throws Exception {
        connector(1); publication.listOnShelf(command()); supplyRule("110", SuggestedSalePriceRule.Operator.ADD,"1");
        pricing.savePlatform(List.of("SKU_1"),suggestedConfig(),1L); seedBuyerAndProjectPricing();
        var held = new CountDownLatch(1); var waiting = new CountDownLatch(1);
        independentInitializerReachedRoot = waiting;
        var pool = Executors.newFixedThreadPool(2);
        try {
            var edit = pool.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(tx -> {
                context.getBean(PlatformSkuMapper.class).selectByIdForUpdate(1L);
                context.getBean(PlatformSkuPricingMapper.class).selectBySkuIdForUpdate(1L);
                supplyRule("120", SuggestedSalePriceRule.Operator.ADD,"2");
                jdbc.update("UPDATE sc_platform_sku SET sku_name='运营新名称' WHERE id=1");
                held.countDown(); await(waiting);
            }));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var change = pool.submit(() -> { Thread.currentThread().setName("independent-buyer-refresh");
                cooperative.updateCommercial("SUP_1",offerNo,line(9000L),1L); publication.listOnShelf(command()); });
            edit.get(20,TimeUnit.SECONDS); change.get(20,TimeUnit.SECONDS);
            assertSuggested("READY",11000L);
            assertThat(jdbc.queryForObject("SELECT sku_name FROM sc_platform_sku WHERE id=1",String.class)).isEqualTo("运营新名称");
        } finally { waiting.countDown(); independentInitializerReachedRoot=null; pool.shutdownNow(); }
        var before = suggestedState(); Long version=offer().getCurrentVersionId();
        jdbc.update("UPDATE sc_project_sku_pricing_version SET override_json='\"broken\"' WHERE id=(SELECT current_version_id FROM sc_project_sku_pricing)");
        cooperative.updateCommercial("SUP_1",offerNo,line(7000L),1L);
        assertThatThrownBy(() -> publication.listOnShelf(command())).isInstanceOf(RuntimeException.class);
        assertThat(suggestedState()).isEqualTo(before);
        assertThat(offer().getCurrentVersionId()).isEqualTo(version);
    }

    @Test void allCandidateBoundariesSwitchMinimumAndOldTasksCannotRestoreChangedRule() throws Exception {
        connector(1); publication.listOnShelf(command());
        var second = new SupplierOfferDO();
        org.springframework.beans.BeanUtils.copyProperties(offer(),second);
        second.setId(null); second.setOfferNo("OFF_SECOND"); second.setOfferCode("SECOND");
        second.setExternalSkuCode("EXT_SECOND"); second.setCurrentVersionId(null); second.setVersion(0L);
        offers.insert(second);
        var version = SupplierOfferVersionDO.builder().build();
        org.springframework.beans.BeanUtils.copyProperties(context.getBean(SupplierOfferVersionMapper.class).selectById(offer().getCurrentVersionId()),version);
        version.setId(null); version.setOfferId(second.getId()); version.setVersionNo(1); version.setExternalSkuCode("EXT_SECOND");
        version.setSupplierSettlementUnitPrice(7000L);
        LocalDateTime start = context.getBean(SupplyChainDatabaseClock.class).now().plusSeconds(2);
        version.setValidFrom(start); version.setValidTo(start.plusSeconds(2));
        context.getBean(SupplierOfferVersionMapper.class).insert(version);
        second.setCurrentVersionId(version.getId()); offers.updateById(second);
        supplyRule("110",SuggestedSalePriceRule.Operator.ADD,"1");
        assertSuggested("READY",10550L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type=?",Long.class,SuggestedPriceBoundaryTimerHandler.TASK_TYPE)).isEqualTo(2L);
        assertThat(suggestedState().get("next_check_at").toString()).contains(start.toLocalDate().toString());
        var resolver=context.getBean(SuggestedSalePriceResolver.class); var sku=context.getBean(PlatformSkuMapper.class).selectById(1L);
        assertThat(resolver.resolve(sku,start.plusNanos(1000000)).amountCents()).isEqualTo(7800L);
        assertThat(resolver.resolve(sku,start.plusSeconds(3)).amountCents()).isEqualTo(10550L);
        var props=context.getBean(TimerEngineProperties.class); props.setEnabled(true); props.setScanInterval(java.time.Duration.ofMillis(50));
        var engine=context.getBean(TimerEngineLifecycle.class); engine.start();
        try {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while (!Long.valueOf(7800L).equals(suggestedState().get("effective_price_cents")) && System.nanoTime()<end) Thread.sleep(30);
            assertSuggested("READY",7800L);
            end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while (!Long.valueOf(10550L).equals(suggestedState().get("effective_price_cents")) && System.nanoTime()<end) Thread.sleep(30);
            assertSuggested("READY",10550L);
        } finally { engine.stop(); }
        transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(tx -> {
            var changed=context.getBean(PlatformSkuMapper.class).selectByIdForUpdate(1L);
            changed.setSuggestedSalePriceRuleJson(SuggestedSalePriceRule.fixed(1234L)); changed.setSuggestedSalePrice(1234L);
            context.getBean(PlatformSkuMapper.class).updateById(changed);
            pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L));
        });
        context.getBean(SuggestedPriceBoundaryTimerHandler.class).handle(null,new ObjectMapper().createObjectNode().put("platformSkuId",1L).put("offerVersionId",version.getId()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_sku_suggested_price_state",Long.class)).isZero();
        assertThat(context.getBean(SuggestedSalePriceResolver.class).resolve(context.getBean(PlatformSkuMapper.class).selectById(1L),context.getBean(SupplyChainDatabaseClock.class).now()).amountCents()).isEqualTo(1234L);
    }

    @Test void formulaWithoutPricingIdentityCreatesUniqueRootAndPendingState() {
        jdbc.update("DELETE FROM sc_platform_sku_pricing"); jdbc.update("DELETE FROM sc_platform_sku_pricing_version");
        supplyRule("110",SuggestedSalePriceRule.Operator.ADD,"1");
        assertSuggested("DEPENDENCY_MISSING",null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM sc_platform_sku_pricing",Long.class)).isNull();
        pricing.initializePlatformDefaults(new PlatformSkuPricingInitializationEvent(List.of("SKU_1"),1L));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version",Long.class)).isEqualTo(1L);
        assertSuggested("DEPENDENCY_MISSING",null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"BUYER_MISSING","BUYER_FOREIGN","PROJECT_MISSING","PROJECT_FOREIGN"})
    void dependencyRebuildNeverRepairsCorruptPublishedChildPointers(String damage) {
        connector(1); publication.listOnShelf(command()); supplyRule("110",SuggestedSalePriceRule.Operator.ADD,"1");
        pricing.savePlatform(List.of("SKU_1"),suggestedConfig(),1L); seedBuyerAndProjectPricing();
        String root=damage.startsWith("BUYER")?"sc_buyer_sku_pricing":"sc_project_sku_pricing";
        if(damage.endsWith("MISSING")) jdbc.update("UPDATE "+root+" SET current_version_id=999999");
        else jdbc.update("UPDATE "+root+"_version SET pricing_id=999999 WHERE id=(SELECT current_version_id FROM "+root+")");
        var state=suggestedState(); Long pointer=offer().getCurrentVersionId();
        Long current=jdbc.queryForObject("SELECT current_version_id FROM "+root,Long.class);
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version",Long.class);
        cooperative.updateCommercial("SUP_1",offerNo,line(9000L),1L);
        assertThatThrownBy(() -> publication.listOnShelf(command())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing or mismatched");
        assertThat(suggestedState()).isEqualTo(state);
        assertThat(offer().getCurrentVersionId()).isEqualTo(pointer);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM "+root,Long.class)).isEqualTo(current);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version",Long.class)).isEqualTo(count);
    }

    @Test void concurrentFirstStateRefreshUsesOneUniqueRootAndOnePendingRevision() throws Exception {
        supplyRule("110",SuggestedSalePriceRule.Operator.ADD,"1");
        jdbc.update("DELETE FROM sc_sku_suggested_price_state"); jdbc.update("DELETE FROM sc_platform_sku_pricing");
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            java.util.List<Future<?>> tasks=new java.util.ArrayList<>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(() -> { ready.countDown(); await(start);
                pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L)); }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            for(var task:tasks) task.get(20,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing",Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_sku_suggested_price_state",Long.class)).isEqualTo(1L);
            assertThat(suggestedState().get("revision")).isEqualTo(1L);
            assertSuggested("DEPENDENCY_MISSING",null);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void firstQuoteWaitsForBoundaryRootAndCannotFreezePreBoundaryAmount() throws Exception {
        connector(1); publication.listOnShelf(command()); supplyRule("110",SuggestedSalePriceRule.Operator.ADD,"1");
        pricing.savePlatform(List.of("SKU_1"),suggestedConfig(),1L); seedBuyerAndProjectPricing();
        var held=new CountDownLatch(1); var waiting=new CountDownLatch(1); independentInitializerReachedRoot=waiting;
        var pool=Executors.newFixedThreadPool(2);
        try {
            var boundary=pool.submit(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(tx -> {
                context.getBean(PlatformSkuPricingMapper.class).selectBySkuIdForUpdate(1L);
                jdbc.update("UPDATE sc_supplier_offer_version SET valid_from=NOW(3)-INTERVAL 2 SECOND,valid_to=NOW(3)-INTERVAL 1 SECOND WHERE id=?",offer().getCurrentVersionId());
                pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L)); held.countDown(); await(waiting);
            }));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var quote=pool.submit(() -> { Thread.currentThread().setName("independent-buyer-refresh");
                return catchThrowable(() -> context.getBean(StandardPricingQuoteService.class)
                        .freezeForProject(new ProjectQuoteKeyContext(1L,1L,1L,"WAITING",1))); });
            boundary.get(20,TimeUnit.SECONDS);
            assertThat(quote.get(20,TimeUnit.SECONDS)).isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
            assertSuggested("DEPENDENCY_MISSING",null);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_standard_pricing_quote",Long.class)).isZero();
        } finally { waiting.countDown(); independentInitializerReachedRoot=null; pool.shutdownNow(); }
    }

    @Test void realZeroSupplyCalculatesSuggestedAmountButTransactionPriceRemainsUnavailable() {
        connector(1); publication.listOnShelf(command()); supplyRule("110",SuggestedSalePriceRule.Operator.ADD,"1");
        jdbc.update("UPDATE sc_supplier_offer_version SET supplier_settlement_unit_price=0 WHERE id=?",offer().getCurrentVersionId());
        pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L));
        assertSuggested("READY",100L);
        assertThat(jdbc.queryForObject("SELECT pricing_status FROM sc_platform_sku_pricing",String.class)).isEqualTo("DEPENDENCY_MISSING");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void corruptNonNullOfferPointerRollsBackTriggerAndPreservesLastValidFact(boolean foreign) {
        connector(1); publication.listOnShelf(command()); supplyRule("110",SuggestedSalePriceRule.Operator.ADD,"1");
        var before=suggestedState(); Long pointer=offer().getCurrentVersionId();
        if (foreign) jdbc.update("UPDATE sc_supplier_offer_version SET offer_id=999999 WHERE id=?",pointer);
        else jdbc.update("DELETE FROM sc_supplier_offer_version WHERE id=?",pointer);
        assertThatThrownBy(() -> transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(tx -> {
            jdbc.update("UPDATE sc_supplier SET status=? WHERE id=1",SupplierStatus.SUSPENDED.getCode());
            pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L));
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("version");
        assertThat(suggestedState()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM sc_supplier WHERE id=1",Integer.class)).isEqualTo((int)SupplierStatus.ACTIVE.getCode());
    }

    @Test void timerSystemFailureRetriesToDeadLetterWithoutCommittingPartialPriceState() throws Exception {
        connector(1); publication.listOnShelf(command()); supplyRule("110",SuggestedSalePriceRule.Operator.ADD,"1");
        var before=suggestedState(); Long count=jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version",Long.class);
        jdbc.update("DELETE FROM sc_supplier_offer_version WHERE id=?",offer().getCurrentVersionId());
        var task=context.getBean(PersistentTimer.class).scheduleOnce(SuggestedPriceBoundaryTimerHandler.TASK_TYPE,
                "integrity-probe",new ObjectMapper().createObjectNode().put("platformSkuId",1L),java.time.Instant.now().minusSeconds(1));
        var properties=context.getBean(TimerEngineProperties.class);
        properties.setEnabled(true); properties.setScanInterval(java.time.Duration.ofMillis(30));
        properties.setBackoffBase(java.time.Duration.ofMillis(10)); properties.setBackoffMax(java.time.Duration.ofMillis(30));
        var engine=context.getBean(TimerEngineLifecycle.class); engine.start();
        try {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (jdbc.queryForObject("SELECT status FROM sc_timer_task WHERE id=?",Integer.class,task.taskId())!=com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper.STATUS_DEAD && System.nanoTime()<end) Thread.sleep(30);
            assertThat(jdbc.queryForObject("SELECT status FROM sc_timer_task WHERE id=?",Integer.class,task.taskId())).isEqualTo((int)com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper.STATUS_DEAD);
            int attempts=context.getBean(PersistentTimerHandlerRegistry.class).maxAttempts(SuggestedPriceBoundaryTimerHandler.TASK_TYPE);
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM sc_timer_task WHERE id=?",Integer.class,task.taskId())).isEqualTo(attempts);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_execution WHERE task_id=?",Integer.class,task.taskId())).isEqualTo(attempts);
            assertThat(suggestedState()).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version",Long.class)).isEqualTo(count);
        } finally { engine.stop(); }
    }

    @Test void actualSupplierProductInventoryAndConnectorChangesInvalidateAndRecoverDynamicPrice() {
        connector(1); publication.listOnShelf(command()); supplyRule("110",SuggestedSalePriceRule.Operator.ADD,"1");
        var suppliers=context.getBean(SupplierProfileCommandService.class);
        suppliers.changeStatus("SUP_1",SupplierStatus.SUSPENDED,1L); assertSuggested("DEPENDENCY_MISSING",null);
        suppliers.changeStatus("SUP_1",SupplierStatus.ACTIVE,1L); assertSuggested("READY",10550L);
        suppliers.changeStatus("SUP_1",SupplierStatus.BLACKLIST,1L); assertSuggested("DEPENDENCY_MISSING",null);
        suppliers.changeStatus("SUP_1",SupplierStatus.ACTIVE,1L); assertSuggested("READY",10550L);
        var products=context.getBean(com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.class);
        products.changeSpuStatus("SPU_1",com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus.INACTIVE,1L);
        assertSuggested("DEPENDENCY_MISSING",null);
        products.changeSpuStatus("SPU_1",com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus.ACTIVE,1L);
        assertSuggested("READY",10550L);
        products.changeSkuStatus("SKU_1",com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus.INACTIVE,1L);
        assertSuggested("DEPENDENCY_MISSING",null);
        products.changeSkuStatus("SKU_1",com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus.ACTIVE,1L);
        assertSuggested("READY",10550L);
        Long sourceId=context.getBean(SupplierOfferVersionMapper.class).selectById(offer().getCurrentVersionId()).getInventorySourceId();
        jdbc.update("UPDATE sc_inventory_source SET status=? WHERE id=?",InventorySourceStatus.DRAFT.getCode(),sourceId);
        pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L)); assertSuggested("DEPENDENCY_MISSING",null);
        var source=context.getBean(InventorySourceMapper.class).selectById(sourceId);
        publication.activateInventorySource(source.getSourceNo(),source.getVersion(),1L); assertSuggested("READY",10550L);
        context.getBean(ConnectorPublicationService.class).retire(new ConnectorPublicationService.RetireConnectorVersionCommand(
                "CON_1",1L,1L,"退役联动","trace","corr","cause"));
        assertSuggested("DEPENDENCY_MISSING",null);
        connector(2); pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(1L));
        assertSuggested("DEPENDENCY_MISSING",null);
        publication.listOnShelf(command()); assertSuggested("READY",10550L);
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
            SupplierCooperativeProductService.class, SupplierSupplyScopeService.class,
            SupplierOfferPublicationService.class, GenericBatchPricingService.class,
            StandardPricingQuoteService.class, ProjectPricingAdmissionService.class,
            SupplierProfileCommandService.class, com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.class,
            com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver.class,
            DatabasePersistentTimer.class, PersistentTimerHandlerRegistry.class, TimerClaimService.class,
            TimerEngineLifecycle.class, DefaultAppendScheduleIdentityLedgerService.class,
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
        @Bean com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort envelopeCrypto() {
            return mock(com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort.class);
        }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean TaxRateDictionaryAdapter dictionary() {
            TaxRateDictionaryAdapter dictionary = mock(TaxRateDictionaryAdapter.class);
            when(dictionary.requireEnabled(any(),anyString())).thenAnswer(call -> new TaxMetadata(call.getArgument(0),call.getArgument(1),null));
            return dictionary;
        }
    }
}
