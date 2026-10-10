package com.aifuli.module.supplychain.tradecore.buyer;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationExecutorPort;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.security.*;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor;
import com.aifuli.module.supplychain.testsupport.RoutingPolicyPresetFixture;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.application.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.*;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.*;
import com.aifuli.module.supplychain.tradecore.supply.application.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * 真实 MySQL/Liquibase 与 Spring 代理验证独立 RR、固定锁序、商业/投影/游标原子及幂等。
 * fixture 仅在本类独占 Testcontainers 空库中通过真实应用服务建立，不接受远端 JDBC 覆盖。
 * 设计见 docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#8-受影响文件与验证门禁。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyerSelectionCatalogLifecycleIntegrationTest {
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private BuyerSelectionCatalogLifecycle lifecycle;
    private CurrentSupplyEligibilityReader reader;
    private String listingNo;
    private Long buyerId;
    private Long skuId;
    private Long supplierId;
    private final DeterministicSqlInterceptor barrier = new DeterministicSqlInterceptor();

    @BeforeAll
    void start() {
        mysql.start();
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var properties = new LinkedHashMap<String, Object>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", mysql.getJdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", mysql.getUsername());
        properties.put("aifuli.supply-chain.datasource.password", mysql.getPassword());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.security.key-version", "test-v1");
        properties.put("aifuli.supply-chain.security.envelope-key-base64", key);
        properties.put("aifuli.supply-chain.security.fingerprint-key-base64", key);
        properties.put("aifuli.supply-chain.connector.allowed-hosts", "api.qinameng.com");
        properties.put("aifuli.supply-chain.connector.allowed-adapter-types", QnmGameOrderV1Metadata.TYPE);
        properties.put("CATALOG_TEST_SECRET", "isolated-test-only");
        context = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).properties(properties).run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        lifecycle = context.getBean(BuyerSelectionCatalogLifecycle.class);
        reader = context.getBean(CurrentSupplyEligibilityReader.class);
        context.getBean("supplyChainSqlSessionFactory", org.apache.ibatis.session.SqlSessionFactory.class)
                .getConfiguration().addInterceptor(barrier);
        seed();
    }

    @AfterAll
    void stop() { if (context != null) context.close(); mysql.stop(); }
    @AfterEach
    void reset() { barrier.reset(); jdbc.update("UPDATE sc_supplier SET status=10 WHERE id=?", supplierId); }

    @Test
    void independentReaderSeesCommittedStopAfterOuterRepeatableReadSnapshot() throws Exception {
        var outer = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var writer = Executors.newSingleThreadExecutor();
        try {
            outer.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT status FROM sc_supplier WHERE id=?", Short.class, supplierId))
                        .isEqualTo((short) 10);
                try {
                    writer.submit(() -> jdbc.update("UPDATE sc_supplier SET status=20 WHERE id=?", supplierId))
                            .get(10, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(jdbc.queryForObject("SELECT status FROM sc_supplier WHERE id=?", Short.class, supplierId))
                        .as("外层 RR 仍是旧视图").isEqualTo((short) 10);
                var eligibility = reader.evaluate(buyerId, null, skuId);
                assertThat(eligibility.availability()).isEqualTo(ListingAvailability.UNAVAILABLE);
                assertThat(eligibility.reason()).isEqualTo("SUPPLIER_NOT_ACTIVE");
                assertThat(jdbc.queryForObject("SELECT status FROM sc_supplier WHERE id=?", Short.class, supplierId))
                        .as("Reader退出后恢复外层旧事务").isEqualTo((short) 10);
            });
        } finally { writer.shutdownNow(); }
    }

    @Test
    void publishingIsAtomicIdempotentAndDoesNotAppendOutbox() {
        long outbox = jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event", Long.class);
        var trace = new ArrayList<String>();
        barrier.use(new DeterministicSqlInterceptor.Hook() {
            public void before(String statement, String sql, Object parameters) {
                if (sql.toUpperCase(Locale.ROOT).contains("FOR UPDATE")) trace.add(statement);
            }
        });
        var first = lifecycle.refreshListing(listingNo, 1L);
        assertThat(first.publicationState()).as("实际刷新结果 %s；真实定价 %s", first,
                jdbc.queryForList("SELECT pricing_status,unavailable_reason,distribution_price_cents,supply_price_cents FROM sc_buyer_sku_pricing_version"))
                .isEqualTo("PUBLISHED");
        assertThat(first.availabilityStatus()).isEqualTo("AVAILABLE");
        assertThat(trace).hasSize(3);
        assertThat(trace.get(0)).contains("BuyerListingMapper");
        assertThat(trace.get(1)).contains("BuyerProductMapper");
        assertThat(trace.get(2)).contains("BuyerSkuPricingMapper");
        var before = commercialCounts();
        assertThat(lifecycle.refreshListing(listingNo, null).sourceBuyerPricingVersionId())
                .isEqualTo(first.sourceBuyerPricingVersionId());
        assertThat(commercialCounts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event", Long.class)).isEqualTo(outbox);
    }

    @Test
    void finalAutoAdmissionSeesNewCommittedPriceOutsideOldRepeatableReadView() throws Exception {
        lifecycle.refreshListing(listingNo, 1L);
        var product = context.getBean(BuyerProductMapper.class).selectByBuyerAndSku(buyerId, skuId);
        var outer = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var writer = Executors.newSingleThreadExecutor();
        try {
            outer.executeWithoutResult(status -> {
                var listing = context.getBean(BuyerListingMapper.class).selectByListingNo(listingNo);
                var version = context.getBean(BuyerListingVersionMapper.class).selectById(listing.getCurrentVersionId());
                var old = context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId()).getCurrentVersionId();
                // 建立旧RR视图后，在另一个真实事务提交新READY来源，不刷新LST。
                try {
                    writer.submit(() -> context.getBean(GenericBatchPricingService.class).saveBuyer(
                            "BYR_001", List.of(product.getBuyerProductCode()), inherited(), 1L)).get(10, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId()).getCurrentVersionId())
                        .as("外层普通读仍为旧RR来源").isEqualTo(old);
                assertThatThrownBy(() -> lifecycle.requireCurrentAutoAdmission(listing, version))
                        .as("当前锁读应得到新来源及新版本，稳定业务拒绝而不是RR导致悬空版本500")
                        .isInstanceOf(ProductNotSaleableException.class);
                status.setRollbackOnly(); // 守卫MANDATORY抛出运行时异常会标记外层回滚。
            });
        } finally {
            writer.shutdownNow();
            lifecycle.refreshListing(listingNo, 1L);
        }
    }

    @Test
    void cursorFailureRollsBackVersionPointerProjectionAndCommercialVersion() {
        // 通过原定价服务制造真实新来源；在游标SQL边界注入故障，验证整个refresh回滚而价格已提交保留。
        var product = context.getBean(BuyerProductMapper.class).selectByBuyerAndSku(buyerId, skuId);
        var buyerCode = context.getBean(BuyerMapper.class).selectById(buyerId).getBuyerCode();
        context.getBean(GenericBatchPricingService.class).saveBuyer(buyerCode,
                List.of(product.getBuyerProductCode()), inherited(), 1L);
        var before = snapshot();
        barrier.use(new DeterministicSqlInterceptor.Hook() {
            public void before(String statement, String sql, Object parameters) {
                if (statement.endsWith("BuyerCatalogChangeMapper.insert")) throw new IllegalStateException("cursor fixture failure");
            }
        });
        assertThatThrownBy(() -> lifecycle.refreshListing(listingNo, 1L)).isInstanceOf(Exception.class);
        barrier.reset();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void committedBatchPricingSurvivesPostSaveRefreshRollbackAndExplicitRetryPublishes() {
        var product = context.getBean(BuyerProductMapper.class).selectByBuyerAndSku(buyerId, skuId);
        var beforeSource = context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId()).getCurrentVersionId();
        var beforeCatalog = snapshot();
        var services = context.getBeansOfType(BuyerCatalogManagementService.class);
        assertThat(services).as("真实管理编排代理必须装配").hasSize(1);
        var management = services.values().iterator().next();
        barrier.use(new DeterministicSqlInterceptor.Hook() {
            public void before(String statement, String sql, Object parameters) {
                if (statement.endsWith("BuyerCatalogChangeMapper.insert")) throw new IllegalStateException("post-save fixture failure");
            }
        });
        assertThat(management.saveBuyerPricing("BYR_001", List.of(product.getBuyerProductCode()), inherited(), 1L)).isTrue();
        assertThat(context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId()).getCurrentVersionId())
                .as("原整批价格已提交，不能随独立refresh回滚").isNotEqualTo(beforeSource);
        barrier.reset();
        assertThat(snapshot()).as("失败refresh没有商业/指针/投影/游标残留").isEqualTo(beforeCatalog);
        var result = management.recalculate("BYR_001", product.getBuyerProductCode(), 1L);
        assertThat(result.publicationState()).isEqualTo("PUBLISHED");
        assertThat(result.sourceBuyerPricingVersionId()).isEqualTo(
                context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId()).getCurrentVersionId());
    }

    @Test
    void managementNeverRejectsOuterTransactionBeforeSavingPrices() {
        var services = context.getBeansOfType(BuyerCatalogManagementService.class);
        assertThat(services).hasSize(1);
        var management = services.values().iterator().next();
        var product = context.getBean(BuyerProductMapper.class).selectByBuyerAndSku(buyerId, skuId);
        var source = context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId()).getCurrentVersionId();
        var tx = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        tx.executeWithoutResult(status -> assertThatThrownBy(() -> management.saveBuyerPricing(
                "BYR_001", List.of(product.getBuyerProductCode()), inherited(), 1L))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class));
        assertThat(context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId()).getCurrentVersionId())
                .isEqualTo(source);
    }

    @Test
    void delayedRefreshReadsNewCommittedSourceInsteadOfReplayingOldPrice() throws Exception {
        var product = context.getBean(BuyerProductMapper.class).selectByBuyerAndSku(buyerId, skuId);
        CountDownLatch delayed = new CountDownLatch(1), release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        barrier.use(new DeterministicSqlInterceptor.Hook() {
            public void before(String statement, String sql, Object parameters) throws Exception {
                if (Thread.currentThread().getName().equals("delayed-catalog-refresh")
                        && statement.contains("BuyerListingMapper") && sql.toUpperCase(Locale.ROOT).contains("FOR UPDATE")) {
                    delayed.countDown(); DeterministicSqlInterceptor.await(release);
                }
            }
        });
        try {
            var late = worker.submit(() -> {
                Thread.currentThread().setName("delayed-catalog-refresh");
                return lifecycle.refreshListing(listingNo, 1L);
            });
            DeterministicSqlInterceptor.await(delayed);
            context.getBean(GenericBatchPricingService.class).saveBuyer("BYR_001",
                    List.of(product.getBuyerProductCode()), inherited(), 1L);
            var latest = lifecycle.refreshListing(listingNo, 1L);
            var counts = commercialCounts();
            release.countDown();
            assertThat(late.get(10, TimeUnit.SECONDS).sourceBuyerPricingVersionId())
                    .isEqualTo(latest.sourceBuyerPricingVersionId());
            assertThat(commercialCounts()).as("迟到调用不得新增旧商业版本或游标").isEqualTo(counts);
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test
    void realControllerPreservesBooleanAndRecalculationAndReadExposeActualCatalogResult() {
        var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var original = security.getAuthentication();
        var user = new com.aifuli.framework.security.core.LoginUser(); user.setId(1L);
        user.setUserType(com.aifuli.framework.common.enums.UserTypeEnum.ADMIN.getValue());
        security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
        var product = context.getBean(BuyerProductMapper.class).selectByBuyerAndSku(buyerId, skuId);
        var controller = context.getBean(com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.class);
        var request = new com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.BuyerProductBatchPricingReqVO();
        request.setBuyerProductCodes(List.of(product.getBuyerProductCode()));
        var rule = new com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.FormulaRuleReqVO();
        rule.setMode("CUSTOM"); rule.setBase("SUPPLY_PRICE"); rule.setPercentage(new java.math.BigDecimal("200"));
        rule.setOperator("ADD"); rule.setOperand(java.math.BigDecimal.ZERO);
        request.setDistributionRule(rule); request.setSalesInherited(true); request.setServiceFeeInherited(true);
        request.setHideInherited(true); request.setTaxInherited(true);
        assertThat(controller.updateProductPricing("BYR_001", request).getData()).isTrue();
        var result = controller.recalculateAcceptanceCatalog("BYR_001", product.getBuyerProductCode()).getData();
        assertThat(result.publicationState()).isEqualTo("PUBLISHED");
        var view = controller.pageProducts("BYR_001", null, null, null, null, 1, 20).getData().getList().getFirst();
        assertThat(view.canonicalBuyerSkuCode()).isEqualTo(product.getBuyerProductCode());
        assertThat(view.acceptanceListingNo()).isEqualTo(listingNo);
        assertThat(view.sourceBuyerPricingVersionId()).isEqualTo(result.sourceBuyerPricingVersionId());
        assertThat(view.publicationState()).isEqualTo("PUBLISHED");
        } finally { security.setAuthentication(original); }
    }

    private void seed() {
        var catalog = context.getBean(ProductCatalogCommandService.class);
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand("BRD_001", "测试", null, null, 0, 1L));
        catalog.changeBrandStatus("BRD_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand("CAT_001", "测试", null, 0, 1L));
        catalog.changeCategoryStatus("CAT_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand("SPU_001", "BRD_001", "CAT_001",
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", "测试商品", null, null, null, 1L));
        catalog.changeSpuStatus("SPU_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand("SKU_001_001", "SPU_001", "测试SKU",
                "DIRECT_RECHARGE", 10000L, "CNY", 1L));
        catalog.changeSkuStatus("SKU_001_001", CatalogEntityStatus.ACTIVE, 1L);
        skuId = context.getBean(PlatformSkuMapper.class).selectByCode("SKU_001_001").getId();
        context.getBean(BuyerCommandService.class).createBuyer(new BuyerCommandService.CreateBuyerCommand(
                null, "测试分销商", "测试", null, null, "AIFULI_MALL", "catalog-test",
                LocalDateTime.of(2026, 1, 1, 0, 0), 1L));
        buyerId = context.getBean(BuyerMapper.class).selectByBuyerCode("BYR_001").getId();
        var configuration = context.getBean(SupplierConfigurationCommandService.class);
        var supplier = configuration.createSupplier(new SupplierConfigurationCommandService.CreateSupplierCommand(
                null, "测试供应商", "测试", "STANDARD_SUPPLIER", null, 1L));
        supplierId = context.getBean(com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper.class)
                .selectByCode(supplier).getId();
        var slot = configuration.createCredentialSlot(new SupplierConfigurationCommandService.CreateCredentialSlotCommand(
                "ENV", "env:CATALOG_TEST_SECRET", "test-only", "v1", 1L));
        var draft = configuration.createConnectorDraft(new SupplierConfigurationCommandService.CreateConnectorDraftCommand(
                supplier, "TEST", QnmGameOrderV1Metadata.TYPE, "https://api.qinameng.com", "test", slot, "V1",
                "/callbacks/qnm/game-order/v1", 3000, 10000, 100, 20, "{}", 1L));
        configuration.initializeMappings(draft.connectorVersionId(), 1L);
        context.getBean(ConnectorValidationService.class).validate(new ConnectorValidationService.ValidateConnectorCommand(
                draft.connectorVersionId(), Set.of("ISSUE_ASSET", "CREATE_RECHARGE", "QUERY_ORDER", "RECEIVE_CALLBACK", "QUERY_BALANCE"),
                Duration.ofMinutes(10), 1L, "测试配置", "catalog-test"));
        context.getBean(ConnectorPublicationService.class).publish(new ConnectorPublicationService.PublishConnectorCommand(
                draft.connectorNo(), draft.connectorVersionId(), 0L, 1L, "测试发布", "catalog-test", null, null));
        var offers = context.getBean(SupplierOfferPublicationService.class);
        var inventory = offers.createRemoteOnDemandInventory(new SupplierOfferPublicationService.CreateInventorySourceCommand(
                supplier, "SKU_001_001", "TEST", 1L));
        offers.activateInventorySource(inventory, 0L, 1L);
        var offer = offers.createOffer(new SupplierOfferPublicationService.CreateOfferCommand(supplier, "SKU_001_001", "TEST", 1L));
        var profile = context.getBean(AdapterMetadataRegistry.class).require(QnmGameOrderV1Metadata.TYPE).defaultExecutionProfile();
        var now = context.getBean(SupplyChainDatabaseClock.class).now();
        offers.publish(new SupplierOfferPublicationService.PublishOfferCommand(offer, 0L, "TEST", 100L,
                draft.connectorVersionId(), inventory, 1L, 1L, 1L, "CREATE_RECHARGE", profile.completionMode().code(),
                profile.createIdempotencyMode().code(), profile.unknownResultPolicy().code(), profile.partialDeliveryMode().code(),
                profile.resultMultiplicity().code(), profile.fulfillmentConfigJson(), now.minusSeconds(1), null,
                "测试供给", 1L, "catalog-test", null, null));
        RoutingPolicyPresetFixture.deactivatePresetPolicy(jdbc);
        var publication = context.getBean(RoutingPolicyPublicationService.class);
        var rule = new RoutingPolicyPublicationService.RuleDraft("GLOBAL_DEFAULT", "测试默认", "FIXED", supplier,
                "ALL", List.of(), "ALL", List.of(), "ALL", List.of(), "ALL", List.of(), null,
                "STABLE", "PRICE", "COMPOSITE", 0, (short) 10, true, List.of());
        var policy = publication.createDraft(new RoutingPolicyPublicationService.CreateDraftCommand("CATALOG_TEST", "测试",
                "测试", now.minusSeconds(1), null, List.of(rule), 1L, 40, 30, 30));
        publication.publish(new RoutingPolicyPublicationService.PublishCommand(policy.policyVersionId(), 0L,
                "测试策略", 1L, "catalog-test", null, null));
        context.getBean(GenericBatchPricingService.class).initializePlatformDefaults(
                new PlatformSkuPricingInitializationEvent(List.of("SKU_001_001"), 1L));
        var codes = context.getBean(BuyerProductCommandService.class).addProducts("BYR_001", List.of(
                new BuyerProductCommandService.ProductLine("SKU_001_001", null, null, null, null, inherited())), 1L);
        listingNo = context.getBean(BuyerListingMapper.class).selectByBuyerAndSku(buyerId, skuId).getListingNo();
        assertThat(codes).hasSize(1);
    }

    private static GenericBatchPricingService.ProjectOverrides inherited() {
        // 旧createSku只设置面值，市场价为0；真实分销规则以供货价200%形成READY，不用SQL补价。
        var distribution = new GenericPricingCalculator.FormulaRule(GenericPricingCalculator.PriceMode.CUSTOM,
                GenericPricingCalculator.PriceBase.SUPPLY_PRICE, new java.math.BigDecimal("200"),
                GenericPricingCalculator.FixedOperator.ADD, java.math.BigDecimal.ZERO);
        return new GenericBatchPricingService.ProjectOverrides(false, distribution, true, null, true, null,
                true, null, true, null);
    }

    private Map<String, Long> commercialCounts() {
        var counts = new TreeMap<String, Long>();
        for (var table : List.of("sc_buyer_listing_version", "sc_buyer_catalog_change")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var result = new TreeMap<String, List<Map<String, Object>>>();
        for (var table : List.of("sc_buyer_listing", "sc_buyer_listing_version", "sc_buyer_listing_availability", "sc_buyer_catalog_change")) {
            var rows = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id");
            rows.forEach(row -> row.replaceAll((k, v) -> v instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : v));
            result.put(table, rows);
        }
        return result;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({BuyerAdminIntegrationTest.TestConfiguration.class, BuyerCommandService.class, OutboxEventRepository.class,
             CurrentSupplyEligibilityReader.class, BuyerSelectionCatalogLifecycle.class, BuyerCatalogManagementService.class,
             com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.class, SupplierRoutingApplicationService.class,
            RoutingPolicyPublicationService.class, QnmGameOrderV1Metadata.class, AdapterMetadataRegistry.class,
            ClosedJsonSchemaValidator.class, EnvironmentCredentialProvider.class, SupplierEndpointPolicy.class,
            AdapterTypeAllowList.class, SupplierConfigurationCommandService.class, ConnectorCredentialBindingValidator.class,
            ConnectorValidationRecordService.class, ConnectorValidationService.class, ConnectorPublicationService.class,
            SupplierOfferPublicationService.class, PublishedConnectorConfigurationResolver.class})
    static class Config {
        /** 本测试只验证本地支持资格；execute一旦被误调用立即失败，不伪装远程履约完成。 */
        @Bean OperationExecutorPort executor() {
            return new OperationExecutorPort() {
                public boolean supports(String type) { return QnmGameOrderV1Metadata.TYPE.equals(type); }
                public OperationOutcome execute(OperationTicket ticket) { throw new AssertionError("Reader must not execute"); }
            };
        }
    }
}
