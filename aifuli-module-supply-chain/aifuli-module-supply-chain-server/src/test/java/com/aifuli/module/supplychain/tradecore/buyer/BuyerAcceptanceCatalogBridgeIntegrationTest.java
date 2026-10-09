package com.aifuli.module.supplychain.tradecore.buyer;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.CreateBuyerCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.application.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.*;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.*;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController;
import com.aifuli.module.supplychain.interfaceapi.admin.M1MasterDataAdminController;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.sql.SQLException;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.FormulaRuleReqVO;
import com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor;
import static com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor.await;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;

/** 真实管理Controller及MySQL身份/兼容命名空间门禁；见桥接设计§13.2/§13.3。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyerAcceptanceCatalogBridgeIntegrationTest {
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private ConfigurableApplicationContext context;
    private ProductCatalogCommandService catalog;
    private BuyerProductCommandService selections;
    private JdbcTemplate jdbc;
    private final AtomicInteger buyers = new AtomicInteger();
    private final DeterministicSqlInterceptor sqlBarrier = new DeterministicSqlInterceptor();
    @BeforeAll
    void start() {
        mysql.start();
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        context = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", mysql.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username", mysql.getUsername(),
                        "aifuli.supply-chain.datasource.password", mysql.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver",
                        "aifuli.supply-chain.security.key-version", "test-v1",
                        "aifuli.supply-chain.security.envelope-key-base64", key,
                        "aifuli.supply-chain.security.fingerprint-key-base64", key)).run();
        catalog = context.getBean(ProductCatalogCommandService.class);
        selections = context.getBean(BuyerProductCommandService.class);
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        context.getBean("supplyChainSqlSessionFactory", org.apache.ibatis.session.SqlSessionFactory.class)
                .getConfiguration().addInterceptor(sqlBarrier);
        seedCatalog(catalog);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand("SKU_001_101", "SPU_001", "第二SKU",
                FulfillmentType.DIRECT_RECHARGE.name(), 10000L, "CNY", 1L));
        catalog.changeSkuStatus("SKU_001_101", CatalogEntityStatus.ACTIVE, 1L);
        context.getBean(GenericBatchPricingService.class).initializePlatformDefaults(
                new PlatformSkuPricingInitializationEvent(List.of("SKU_001_100", "SKU_001_101"), 1L));
    }
    @AfterAll
    void stop() { if (context != null) context.close(); mysql.stop(); }
    @AfterEach void resetBarrier() { sqlBarrier.reset(); }

    @Test
    void reservedPrefixUsesDatabaseCollationIncludingCombiningCharacterLength() {
        for (String code : List.of("BPD_x", "bpd_x", "  bpd_x  ", "B\u0301PD_x", "bṕd_x")) {
            String buyer = newBuyer();
            assertThatThrownBy(() -> catalog.createBuyerListing(new ProductCatalogCommandService.CreateBuyerListingCommand(
                    buyer, "SKU_001_100", code, 1L))).isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(BUYER_CATALOG_CODE_CONFLICT.getCode());
            assertThat(count("sc_buyer_listing", buyer)).isZero();
        }
    }
    @Test
    void realMapperConvertsBothBindingsUnderUtf8mb3AndUtf8mb4ConnectionCharsets() throws Exception {
        var factory = context.getBean("supplyChainSqlSessionFactory", org.apache.ibatis.session.SqlSessionFactory.class);
        for (String charset : List.of("utf8mb3", "utf8mb4")) {
            // 两种真实连接字符集均能表示组合字符；不以手写等价SQL代替受测XML Mapper。
            try (var session = factory.openSession(); var statement = session.getConnection().createStatement()) {
                String originalCharset, originalCollation;
                try (var result = statement.executeQuery("SELECT @@character_set_connection,@@collation_connection")) {
                    assertThat(result.next()).isTrue();
                    originalCharset = result.getString(1); originalCollation = result.getString(2);
                }
                try {
                    statement.execute("SET NAMES " + charset);
                    try (var result = statement.executeQuery("SELECT @@character_set_connection")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo(charset);
                    }
                    var mapper = session.getMapper(BuyerListingMapper.class);
                    for (String code : List.of("BPD_x", "bpd_x", "B\u0301PD_x", "bṕd_x")) {
                        assertThat(mapper.isReservedBuyerProductCode(code, "BPD_")).as("%s / %s", charset, code).isTrue();
                    }
                    assertThat(mapper.isReservedBuyerProductCode("外部-é—编码", "BPD_")).isFalse();
                } finally {
                    // 归还池连接之前恢复数据库返回的原始设置，避免影响其它真实SQL测试。
                    statement.execute("SET NAMES " + originalCharset + " COLLATE " + originalCollation);
                }
            }
        }
    }
    @Test
    void concurrentManualReuseRejectsAnotherSkuBindingOfActualBpdAndCaseTrimVariants() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            String buyer = newBuyer();
            String no = catalog.createBuyerListing(new ProductCatalogCommandService.CreateBuyerListingCommand(
                    buyer, "SKU_001_101", "并发旧别名", 1L));
            var manual = context.getBean(BuyerListingMapper.class).selectByListingNo(no);
            var commercialBefore = commercialSnapshot();
            var attemptedCode = new AtomicReference<String>();
            CountDownLatch reusingManual = new CountDownLatch(1), competingRequestFinished = new CountDownLatch(1);
            AtomicBoolean paused = new AtomicBoolean();
            sqlBarrier.use(new DeterministicSqlInterceptor.Hook() {
                public void after(String statement, String sql, Object params) throws Exception {
                    if (!Thread.currentThread().getName().equals("manual-reuse")) return;
                    if (statement.endsWith("BuyerProductMapper.insert")) attemptedCode.set(row(params, BuyerProductDO.class).getBuyerProductCode());
                    if (statement.contains("BuyerListingMapper") && sql.contains("platform_sku_id")
                            && sql.contains("WHERE") && !sql.toUpperCase().contains("FOR UPDATE")
                            && paused.compareAndSet(false, true)) {
                        assertThat(attemptedCode.get()).isNotBlank();
                        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        reusingManual.countDown(); await(competingRequestFinished);
                    }
                }
            });
            try (var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "manual-reuse"))) {
                var selection = executor.submit(() -> selectThroughController(buyer, "SKU_001_101"));
                try {
                    await(reusingManual);
                    String code = switch (variant) {
                        case 0 -> attemptedCode.get();
                        case 1 -> attemptedCode.get().toLowerCase(Locale.ROOT);
                        default -> "  " + attemptedCode.get().toLowerCase(Locale.ROOT) + "  ";
                    };
                    var request = new M1MasterDataAdminController.CreateListingReqVO();
                    request.setBuyerCode(buyer); request.setPlatformSkuCode("SKU_001_100"); request.setBuyerSkuCode(code);
                    assertThatThrownBy(() -> context.getBean(M1MasterDataAdminController.class).createListing(request))
                            .isInstanceOf(ServiceException.class).extracting(e -> ((ServiceException) e).getCode())
                            .isEqualTo(BUYER_CATALOG_CODE_CONFLICT.getCode());
                } finally { competingRequestFinished.countDown(); }
                assertThat(selection.get(30, TimeUnit.SECONDS)).containsExactly(attemptedCode.get());
            }
            sqlBarrier.reset();
            var product = context.getBean(BuyerProductMapper.class).selectByCode(attemptedCode.get());
            assertThat(context.getBean(BuyerListingMapper.class).selectByListingNo(no)).isEqualTo(manual);
            assertThat(context.getBean(BuyerAcceptanceCatalogResolver.class).resolve(product.getBuyerId(), attemptedCode.get()).listingId()).isEqualTo(manual.getId());
            assertThat(count("sc_buyer_listing", buyer)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_listing WHERE buyer_id=? AND platform_sku_id<>?", Long.class, manual.getBuyerId(), manual.getPlatformSkuId())).isZero();
            assertThat(commercialSnapshot()).isEqualTo(commercialBefore);
        }
    }
    @Test
    void newNonAsciiExternalCodeIsTrimmedAndSavedWithoutAsciiRestriction() {
        String buyer = newBuyer();
        String no = catalog.createBuyerListing(new ProductCatalogCommandService.CreateBuyerListingCommand(
                buyer, "SKU_001_100", "  外部-é—编码  ", 1L));
        assertThat(context.getBean(BuyerListingMapper.class).selectByListingNo(no).getBuyerSkuCode())
                .isEqualTo("外部-é—编码");
    }
    @Test
    void realSelectionControllerCreatesDraftUnknownAndOldPublishControllerRejectsExternalPrice() {
        String buyer = newBuyer();
        String code = selectThroughController(buyer, "SKU_001_100").getFirst();
        var product = context.getBean(BuyerProductMapper.class).selectByCode(code);
        var listing = context.getBean(BuyerListingMapper.class).selectByBuyerAndSkuCode(product.getBuyerId(), code);
        assertThat(listing.getBuyerProductId()).isEqualTo(product.getId());
        assertThat(listing.getManagementMode()).isEqualTo("AUTO_SELECTION");
        assertThat(listing.getStatus()).isEqualTo(BuyerListingStatus.DRAFT.getCode());
        assertThat(listing.getCurrentVersionId()).isNull();
        var projection = context.getBean(BuyerListingAvailabilityMapper.class).selectByListingId(listing.getId());
        assertThat(projection.getAvailability()).isEqualTo(ListingAvailability.UNKNOWN.getCode());
        assertThat(projection.getReasonCode()).isEqualTo("NOT_EVALUATED");
        var price = context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId());
        assertThat(price.getCurrentVersionId()).isNotNull();
        var commercialBefore = commercialSnapshot();
        var request = new M1MasterDataAdminController.PublishListingReqVO();
        request.setListingNo(listing.getListingNo()); request.setExpectedListingVersion(0L);
        request.setBuyerSettlementUnitPrice(999L); request.setDisplayName("外部价");
        request.setValidFrom(LocalDateTime.of(2026, 10, 4, 0, 0)); request.setPublishReason("测试");
        assertThatThrownBy(() -> context.getBean(M1MasterDataAdminController.class).publishListing(request))
                .isInstanceOf(ServiceException.class).extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(BUYER_CATALOG_AUTO_MANAGED.getCode());
        var status = new M1MasterDataAdminController.ChangeListingStatusReqVO();
        status.setListingNo(listing.getListingNo()); status.setExpectedListingVersion(0L); status.setTargetStatus("PUBLISHED");
        assertThatThrownBy(() -> context.getBean(M1MasterDataAdminController.class).changeListingStatus(status))
                .isInstanceOf(ServiceException.class).extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(BUYER_CATALOG_NOT_READY.getCode());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_listing_version WHERE listing_id=?", Long.class, listing.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_catalog_change WHERE listing_id=?", Long.class, listing.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event WHERE target_name='buyer-selection-catalog-refresh'", Long.class)).isZero();
        assertThat(commercialSnapshot()).isEqualTo(commercialBefore);
    }
    @Test
    void manualListingIsReadOnlyReusedAndBpdRevivalKeepsSameIdentities() {
        String buyer = newBuyer();
        String no = catalog.createBuyerListing(new ProductCatalogCommandService.CreateBuyerListingCommand(buyer, "SKU_001_100", "原别名", 1L));
        var before = context.getBean(BuyerListingMapper.class).selectByListingNo(no);
        String code = selectThroughController(buyer, "SKU_001_100").getFirst();
        selections.retire(buyer, code, 1L);
        assertThat(selectThroughController(buyer, "SKU_001_100")).containsExactly(code);
        var after = context.getBean(BuyerListingMapper.class).selectByListingNo(no);
        assertThat(after).isEqualTo(before);
        assertThat(context.getBean(BuyerAcceptanceCatalogResolver.class).resolve(before.getBuyerId(), code).listingId()).isEqualTo(before.getId());
    }
    @Test
    void autoRemovalAndRevivalKeepOriginalProductCodeIdAndDraftListing() {
        String buyer = newBuyer();
        String code = selectThroughController(buyer, "SKU_001_100").getFirst();
        var product = context.getBean(BuyerProductMapper.class).selectByCode(code);
        var listing = context.getBean(BuyerListingMapper.class).selectByBuyerAndSku(product.getBuyerId(), product.getPlatformSkuId());
        var commercialBefore = commercialSnapshot();
        Long pricingId = context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId()).getId();
        long versions = jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_sku_pricing_version WHERE pricing_id=?", Long.class, pricingId);
        selections.retire(buyer, code, 1L);
        assertThat(selectThroughController(buyer, "SKU_001_100")).containsExactly(code);
        assertThat(context.getBean(BuyerProductMapper.class).selectByCode(code).getId()).isEqualTo(product.getId());
        assertThat(context.getBean(BuyerListingMapper.class).selectByListingNo(listing.getListingNo())).isEqualTo(listing);
        assertThat(commercialSnapshot()).isEqualTo(commercialBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_sku_pricing_version WHERE pricing_id=?", Long.class, pricingId)).isEqualTo(versions + 1);
    }
    @Test
    void retiredAutoRejectsRevivalAndEnsureIdentityWithEntireTwoRowBatchRolledBack() {
        String buyer = newBuyer();
        String code = selectThroughController(buyer, "SKU_001_101").getFirst();
        var product = context.getBean(BuyerProductMapper.class).selectByCode(code);
        var listing = context.getBean(BuyerListingMapper.class).selectByBuyerAndSku(product.getBuyerId(), product.getPlatformSkuId());
        changeStatusThroughController(listing, BuyerListingStatus.RETIRED);
        selections.retire(buyer, code, 1L);
        var before = selectionSnapshot();
        assertThatThrownBy(() -> selectThroughController(buyer, "SKU_001_100", "SKU_001_101"))
                .isInstanceOf(ServiceException.class).extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(BUYER_CATALOG_RETIRED.getCode());
        assertThat(selectionSnapshot()).isEqualTo(before);
        assertThatThrownBy(() -> selections.ensureIdentity(code, 1L))
                .isInstanceOf(ServiceException.class).extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(BUYER_CATALOG_RETIRED.getCode());
        assertThat(selectionSnapshot()).isEqualTo(before);
    }
    @Test
    void publishedAndSuspendedManualReusePreservesEveryCommercialVersionPriceAndLifecycle() {
        for (boolean suspended : List.of(false, true)) {
            String buyer = newBuyer();
            String no = catalog.createBuyerListing(new ProductCatalogCommandService.CreateBuyerListingCommand(
                    buyer, "SKU_001_100", "人工商业原码", 1L));
            publishManualThroughController(no, 0L, 8123L, "原人工版本一");
            publishManualThroughController(no, 1L, 9234L, "原人工版本二");
            var listing = context.getBean(BuyerListingMapper.class).selectByListingNo(no);
            if (suspended) changeStatusThroughController(listing, BuyerListingStatus.SUSPENDED);
            listing = context.getBean(BuyerListingMapper.class).selectByListingNo(no);
            var before = commercialSnapshot();
            String code = selectThroughController(buyer, "SKU_001_100").getFirst();
            selections.retire(buyer, code, 1L);
            assertThat(selectThroughController(buyer, "SKU_001_100")).containsExactly(code);
            assertThat(context.getBean(BuyerListingMapper.class).selectByListingNo(no)).isEqualTo(listing);
            assertThat(jdbc.queryForList("SELECT buyer_settlement_unit_price FROM sc_buyer_listing_version WHERE listing_id=? ORDER BY version_no", Long.class, listing.getId())).containsExactly(8123L, 9234L);
            assertThat(commercialSnapshot()).isEqualTo(before);
        }
    }
    @Test
    void realStatusControllerCannotResumeIsolatedSuspendedAutoEvenWithCurrentCommercialVersion() {
        String buyer = newBuyer();
        String code = selectThroughController(buyer, "SKU_001_100").getFirst();
        var product = context.getBean(BuyerProductMapper.class).selectByCode(code);
        var listing = context.getBean(BuyerListingMapper.class).selectByBuyerAndSku(product.getBuyerId(), product.getPlatformSkuId());
        // 隔离负例：A没有自动publisher，此未来/异常状态只用于失败关闭，绝非成功可售闭环。
        // 不绕旧publisher发布AUTO外部价；构造非空current指针，防止仅测NULL遗漏恢复漏洞。
        Long sourceVersion = context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId()).getCurrentVersionId();
        var version = BuyerListingVersionDO.builder().listingId(listing.getId()).versionNo(1)
                .buyerSettlementUnitPrice(777L).currency("CNY").displayName("隔离AUTO恢复负例")
                .minQuantity(1L).maxQuantity(1L).validFrom(LocalDateTime.of(2026, 10, 1, 0, 0))
                .publishReason("仅失败关闭fixture").publishedBy(1L).publishedAt(LocalDateTime.now()).createdAt(LocalDateTime.now())
                .sourceBuyerPricingVersionId(sourceVersion).commercialSnapshotDigest(new byte[32]).build();
        context.getBean(BuyerListingVersionMapper.class).insert(version);
        jdbc.update("UPDATE sc_buyer_listing SET status=?,current_version_id=? WHERE id=?", BuyerListingStatus.SUSPENDED.getCode(), version.getId(), listing.getId());
        var suspended = context.getBean(BuyerListingMapper.class).selectByListingNo(listing.getListingNo());
        var before = commercialSnapshot();
        assertThatThrownBy(() -> changeStatusThroughController(suspended, BuyerListingStatus.PUBLISHED))
                .isInstanceOf(ServiceException.class).extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(BUYER_CATALOG_NOT_READY.getCode());
        assertThat(commercialSnapshot()).isEqualTo(before);
    }
    @Test
    void activeAndRollbackOnlyOuterTransactionsAreRejectedBeforeWriting() {
        for (boolean rollbackOnly : List.of(false, true)) {
            String buyer = newBuyer();
            var tx = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> {
                if (rollbackOnly) status.setRollbackOnly();
                assertThatThrownBy(() -> selectThroughController(buyer, "SKU_001_100"))
                        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            });
            assertThat(count("sc_buyer_product", buyer)).isZero();
            assertThat(count("sc_buyer_listing", buyer)).isZero();
        }
    }
    @Test
    void internalRepairCreatesMissingIdentityWithoutRefreshingAndRejectsOuterTransaction() {
        String buyer = newBuyer();
        String code = selectThroughController(buyer, "SKU_001_100").getFirst();
        var product = context.getBean(BuyerProductMapper.class).selectByCode(code);
        var original = context.getBean(BuyerListingMapper.class).selectByBuyerAndSkuCode(product.getBuyerId(), code);
        // 隔离模拟A升级前真实BPD缺失受理关系；仅删除旧关系，修复成功须由真实应用服务写入。
        jdbc.update("DELETE FROM sc_buyer_listing_availability WHERE listing_id=?", original.getId());
        jdbc.update("DELETE FROM sc_buyer_listing WHERE id=?", original.getId());
        String repairedNo = selections.ensureIdentity(code, 1L);
        assertThat(repairedNo).isNotBlank();
        var repaired = context.getBean(BuyerListingMapper.class).selectByListingNo(repairedNo);
        assertThat(repaired.getBuyerProductId()).isEqualTo(product.getId());
        assertThat(repaired.getStatus()).isEqualTo(BuyerListingStatus.DRAFT.getCode());
        assertThat(selections.ensureIdentity(code, 1L)).isEqualTo(repairedNo);
        var tx = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        tx.executeWithoutResult(status -> assertThatThrownBy(() -> selections.ensureIdentity(code, 1L))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class));
    }
    @Test
    void secondRowRealUniqueRaceRollsBackWholeBatchBeforeIndependentProbeAndRerunsEverything() throws Exception {
        String buyer = newBuyer();
        Long secondSku = context.getBean(PlatformSkuMapper.class).selectByCode("SKU_001_101").getId();
        CountDownLatch secondInsert = new CountDownLatch(1), winnerCommitted = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean(), checkedProbe = new AtomicBoolean();
        List<String> rolledBackCodes = new CopyOnWriteArrayList<>();
        sqlBarrier.use(new DeterministicSqlInterceptor.Hook() {
            public void before(String statement, String sql, Object params) throws Exception {
                if (Thread.currentThread().getName().equals("batch-race") && statement.endsWith("BuyerListingMapper.insert")) {
                    var row = params instanceof BuyerListingDO direct ? direct : (BuyerListingDO) ((Map<?, ?>) params).get("et");
                    rolledBackCodes.add(row.getBuyerSkuCode());
                    if (row.getPlatformSkuId().equals(secondSku) && paused.compareAndSet(false, true)) {
                        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        secondInsert.countDown(); await(winnerCommitted);
                    }
                }
                if (Thread.currentThread().getName().equals("batch-race") && paused.get()
                        && sql.contains("sc_buyer_listing") && sql.toUpperCase().contains("FOR UPDATE")
                        && checkedProbe.compareAndSet(false, true)) {
                    // worker代理已退出；probe当前连接也只能看见获胜MANUAL，不可看见前半批写入。
                    assertThat(count("sc_buyer_product", buyer)).isZero();
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_sku_pricing p JOIN sc_buyer_product b ON b.id=p.buyer_product_id WHERE b.buyer_id=(SELECT id FROM sc_buyer WHERE buyer_code=?)", Long.class, buyer)).isZero();
                }
            }
        });
        try (var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "batch-race"))) {
            Future<List<String>> result = executor.submit(() -> selectThroughController(buyer, "SKU_001_100", "SKU_001_101"));
            try {
                await(secondInsert);
                catalog.createBuyerListing(new ProductCatalogCommandService.CreateBuyerListingCommand(buyer, "SKU_001_101", "获胜旧别名", 1L));
            } finally { winnerCommitted.countDown(); }
            List<String> codes = result.get(30, TimeUnit.SECONDS);
            assertThat(checkedProbe).isTrue();
            assertThat(codes).hasSize(2).doesNotContain(rolledBackCodes.get(0), rolledBackCodes.get(1));
            assertThat(count("sc_buyer_product", buyer)).isEqualTo(2);
            assertThat(count("sc_buyer_listing", buyer)).isEqualTo(2);
            for (String code : codes) {
                var product = context.getBean(BuyerProductMapper.class).selectByCode(code);
                assertThat(product).isNotNull();
                assertThat(context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(product.getId()).getCurrentVersionId()).isNotNull();
            }
        }
    }
    @Test
    void selectionSameSkuCompetitionHasOneCommittedIdentityAndKeepsOrdinaryDuplicateError() throws Exception {
        String buyer = newBuyer();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> action = () -> { await(start); try { return selectThroughController(buyer, "SKU_001_100"); }
                catch (ServiceException error) { return error; } };
            var first = executor.submit(action); var second = executor.submit(action); start.countDown();
            var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(List.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(ServiceException.class::isInstance).map(ServiceException.class::cast))
                    .singleElement().extracting(ServiceException::getCode).isEqualTo(BUYER_PRODUCT_DUPLICATE.getCode());
            assertThat(count("sc_buyer_product", buyer)).isEqualTo(1);
            assertThat(count("sc_buyer_listing", buyer)).isEqualTo(1);
        }
    }
    @Test
    void projectionTechnicalFailureRollsBackAllProductsPricesAndIdentities() {
        String buyer = newBuyer();
        AtomicInteger inserts = new AtomicInteger();
        sqlBarrier.use(new DeterministicSqlInterceptor.Hook() {
            public void before(String statement, String sql, Object params) {
                if (statement.endsWith("BuyerListingAvailabilityMapper.insert") && inserts.incrementAndGet() == 2) {
                    throw new IllegalStateException("隔离投影技术失败");
                }
            }
        });
        assertThatThrownBy(() -> selectThroughController(buyer, "SKU_001_100", "SKU_001_101"))
                .hasStackTraceContaining("隔离投影技术失败");
        assertThat(count("sc_buyer_product", buyer)).isZero();
        assertThat(count("sc_buyer_listing", buyer)).isZero();
    }
    @Test
    void secondRowInlineSaveBuyerRealSqlFailureRollsBackL2InheritedInlinePricesAndCatalog() {
        seedNegativeL1Fixture();
        String buyer = newBuyer();
        var before = selectionSnapshot();
        List<Long> pricingIds = new ArrayList<>();
        List<Integer> writtenVersionAttempts = new ArrayList<>();
        AtomicInteger products = new AtomicInteger(), probes = new AtomicInteger();
        AtomicBoolean failedInSaveBuyer = new AtomicBoolean();
        sqlBarrier.use(new DeterministicSqlInterceptor.Hook() {
            public void before(String statement, String sql, Object params) {
                if (statement.endsWith("BuyerProductMapper.insert")) products.incrementAndGet();
                if (statement.contains("BuyerListingMapper") && sql.toUpperCase().contains("FOR UPDATE")) probes.incrementAndGet();
                if (statement.endsWith("BuyerSkuPricingVersionMapper.insert")) {
                    var version = row(params, BuyerSkuPricingVersionDO.class);
                    if (!pricingIds.contains(version.getPricingId())) pricingIds.add(version.getPricingId());
                    if (pricingIds.size() == 2 && version.getVersionNo() == 2) {
                        assertThat(version.getOverrideJson()).contains("\"distributionInherited\":false");
                        assertThat(writtenVersionAttempts).containsExactly(1, 2, 1);
                        // 两行L2、继承价格、LST/投影和第一行行内价格已在真实worker事务写入。
                        assertThat(count("sc_buyer_product", buyer)).isEqualTo(2);
                        assertThat(count("sc_buyer_listing", buyer)).isEqualTo(2);
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_product_l2_override_version WHERE buyer_product_id IN (SELECT id FROM sc_buyer_product WHERE buyer_id=(SELECT id FROM sc_buyer WHERE buyer_code=?))", Long.class, buyer)).isEqualTo(2);
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_sku_pricing_version WHERE pricing_id IN (?,?)", Long.class, pricingIds.get(0), pricingIds.get(1))).isEqualTo(3);
                        failedInSaveBuyer.set(true);
                        // 只破坏本次SQL入参，真实MySQL返回1406；不伪造异常/worker/probe。
                        version.setCurrency("TOO_LONG");
                    }
                }
            }
            public void after(String statement, String sql, Object params) {
                if (statement.endsWith("BuyerSkuPricingVersionMapper.insert")) writtenVersionAttempts.add(row(params, BuyerSkuPricingVersionDO.class).getVersionNo());
            }
        });
        assertThatThrownBy(() -> selectWithInlinePricingThroughController(buyer, "SKU_001_100", "SKU_001_101"))
                .satisfies(error -> assertSqlError(error, 1406, "currency"));
        assertThat(failedInSaveBuyer).isTrue();
        assertThat(products).hasValue(2); assertThat(probes).hasValue(0);
        sqlBarrier.reset();
        assertThat(selectionSnapshot()).as("包含孤儿版本，所有表完整读回，不以BPD JOIN掩盖残留").isEqualTo(before);
    }
    @Test
    void nonIdentity1062AndOtherRealListingSqlErrorsNeverProbeOrRetryAndRollBackWholeBatch() {
        seedNegativeL1Fixture();
        for (boolean duplicateBusinessNo : List.of(true, false)) {
            String buyer = newBuyer();
            var before = selectionSnapshot();
            AtomicInteger productInserts = new AtomicInteger(), listingInserts = new AtomicInteger(), probes = new AtomicInteger();
            AtomicReference<String> firstListingNo = new AtomicReference<>();
            sqlBarrier.use(new DeterministicSqlInterceptor.Hook() {
                public void before(String statement, String sql, Object params) {
                    if (statement.endsWith("BuyerProductMapper.insert")) productInserts.incrementAndGet();
                    if (statement.contains("BuyerListingMapper") && sql.toUpperCase().contains("FOR UPDATE")) probes.incrementAndGet();
                    if (statement.endsWith("BuyerListingMapper.insert")) {
                        var listing = row(params, BuyerListingDO.class);
                        if (listingInserts.incrementAndGet() == 1) firstListingNo.set(listing.getListingNo());
                        else if (duplicateBusinessNo) listing.setListingNo(firstListingNo.get());
                        else listing.setBuyerSkuCode("x".repeat(65));
                    }
                }
            });
            assertThatThrownBy(() -> selectWithInlinePricingThroughController(buyer, "SKU_001_100", "SKU_001_101"))
                    .satisfies(error -> assertSqlError(error, duplicateBusinessNo ? 1062 : 1406,
                            duplicateBusinessNo ? "uk_sc_listing_no" : "buyer_sku_code"));
            assertThat(productInserts).hasValue(2); assertThat(listingInserts).hasValue(2); assertThat(probes).hasValue(0);
            sqlBarrier.reset();
            assertThat(selectionSnapshot()).isEqualTo(before);
        }
    }
    @Test
    void secondRowRealBuyerProductSkuDuplicatePreservesSqlCauseWithoutProbeOrRetryAndRollsBackWholeBatch() {
        seedNegativeL1Fixture();
        String buyer = newBuyer();
        var before = selectionSnapshot();
        Long firstSku = context.getBean(PlatformSkuMapper.class).selectByCode("SKU_001_100").getId();
        Long secondSku = context.getBean(PlatformSkuMapper.class).selectByCode("SKU_001_101").getId();
        List<Long> attemptedSkus = new ArrayList<>();
        AtomicInteger probes = new AtomicInteger();
        sqlBarrier.use(new DeterministicSqlInterceptor.Hook() {
            public void before(String statement, String sql, Object params) {
                if (statement.contains("BuyerListingMapper") && sql.toUpperCase().contains("FOR UPDATE")) probes.incrementAndGet();
                if (statement.endsWith("BuyerProductMapper.insert")) {
                    var product = row(params, BuyerProductDO.class);
                    attemptedSkus.add(product.getPlatformSkuId());
                    if (attemptedSkus.size() == 2) {
                        assertThat(attemptedSkus).containsExactly(firstSku, secondSku);
                        // 第一行的BPD、L2、继承及行内价格、LST/投影均已由真实worker事务写入。
                        assertThat(count("sc_buyer_product", buyer)).isEqualTo(1);
                        assertThat(count("sc_buyer_listing", buyer)).isEqualTo(1);
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_product_l2_override_version WHERE buyer_product_id IN (SELECT id FROM sc_buyer_product WHERE buyer_id=(SELECT id FROM sc_buyer WHERE buyer_code=?))", Long.class, buyer)).isEqualTo(1);
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_sku_pricing_version WHERE pricing_id IN (SELECT p.id FROM sc_buyer_sku_pricing p JOIN sc_buyer_product b ON b.id=p.buyer_product_id WHERE b.buyer_id=(SELECT id FROM sc_buyer WHERE buyer_code=?))", Long.class, buyer)).isEqualTo(2);
                        // 仅破坏第二行SQL绑定，真实MySQL产生1062/uk_sc_buyer_product_sku，不伪造异常。
                        product.setPlatformSkuId(firstSku);
                    }
                }
            }
        });
        Throwable error = catchThrowable(() -> selectWithInlinePricingThroughController(buyer, "SKU_001_100", "SKU_001_101"));
        assertThat(error).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) error).getCode()).isEqualTo(BUYER_PRODUCT_DUPLICATE.getCode());
        assertThat(attemptedSkus).as("两行各尝试一次，BPD冲突不重试原批次").containsExactly(firstSku, secondSku);
        assertThat(probes).hasValue(0);
        sqlBarrier.reset();
        assertThat(selectionSnapshot()).as("完整14表及孤儿版本必须回滚").isEqualTo(before);
        assertThat(error.getCause()).as("BPD唯一键业务异常直接cause必须保留真实DuplicateKeyException")
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertSqlError(error, 1062, "uk_sc_buyer_product_sku");
    }
    private static <T> T row(Object parameters, Class<T> type) {
        return type.cast(type.isInstance(parameters) ? parameters : ((Map<?, ?>) parameters).get("et"));
    }
    private static void assertSqlError(Throwable error, int code, String keyOrColumn) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                assertThat(sql.getErrorCode()).isEqualTo(code);
                assertThat(sql.getMessage()).contains(keyOrColumn);
                return;
            }
        }
        throw new AssertionError("缺少真实MySQL SQLException", error);
    }
    private Map<String, List<Map<String, Object>>> selectionSnapshot() {
        return tableSnapshot(List.of("sc_buyer_product", "sc_buyer_product_l2_override", "sc_buyer_product_l2_override_version",
                "sc_buyer_product_settlement_price", "sc_buyer_product_settlement_price_version",
                "sc_buyer_sku_pricing", "sc_buyer_sku_pricing_version", "sc_project_sku_pricing", "sc_project_sku_pricing_version",
                "sc_buyer_listing", "sc_buyer_listing_version", "sc_buyer_listing_availability", "sc_buyer_catalog_change", "sc_outbox_event"));
    }
    private Map<String, List<Map<String, Object>>> commercialSnapshot() {
        return tableSnapshot(List.of("sc_buyer_listing", "sc_buyer_listing_version", "sc_buyer_listing_availability", "sc_buyer_catalog_change", "sc_outbox_event"));
    }
    private Map<String, List<Map<String, Object>>> tableSnapshot(List<String> tables) {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : tables) {
            var rows = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1");
            rows.forEach(values -> values.replaceAll((key, value) -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value));
            result.put(table, rows);
        }
        return result;
    }
    private void seedNegativeL1Fixture() {
        // 旧L1无生产发布应用入口；fixture仅支撑L2写后技术回滚负例，不声称从零可售。
        for (String skuCode : List.of("SKU_001_100", "SKU_001_101")) {
            Long skuId = context.getBean(PlatformSkuMapper.class).selectByCode(skuCode).getId();
            if (context.getBean(PlatformSettlementPriceMapper.class).selectById(skuId) != null) continue;
            var now = LocalDateTime.now();
            var version = PlatformSettlementPriceVersionDO.builder().platformSkuId(skuId).versionNo(1)
                    .unitPriceCents(10000L).currency("CNY").publishedBy(1L).publishedAt(now).createdAt(now).build();
            context.getBean(PlatformSettlementPriceVersionMapper.class).insert(version);
            context.getBean(PlatformSettlementPriceMapper.class).insert(PlatformSettlementPriceDO.builder()
                    .platformSkuId(skuId).currentVersionId(version.getId()).version(0L).updatedAt(now).build());
        }
    }
    private List<String> selectWithInlinePricingThroughController(String buyer, String... skus) {
        var request = new BuyerAdminController.AddBuyerProductsReqVO();
        request.setItems(Arrays.stream(skus).map(sku -> {
            var item = new BuyerAdminController.BuyerProductItemReqVO(); item.setPlatformSkuCode(sku);
            item.setL2AdjustMode("EQUAL_L1");
            var pricing = new BuyerAdminController.BuyerProductCreatePricingReqVO();
            var rule = new FormulaRuleReqVO(); rule.setMode("DIRECT_MARKET_PRICE");
            pricing.setDistributionInherited(false); pricing.setDistributionRule(rule);
            pricing.setSalesInherited(true); pricing.setServiceFeeInherited(true); pricing.setHideInherited(true); pricing.setTaxInherited(true);
            item.setPricing(pricing); return item;
        }).toList());
        return context.getBean(BuyerAdminController.class).addProducts(buyer, request).getData();
    }
    private void publishManualThroughController(String no, long expected, long price, String display) {
        var request = new M1MasterDataAdminController.PublishListingReqVO();
        request.setListingNo(no); request.setExpectedListingVersion(expected); request.setBuyerSettlementUnitPrice(price);
        request.setDisplayName(display); request.setPublishReason("人工真实商业版本");
        request.setValidFrom(LocalDateTime.of(2026, 10, 1, 0, 0)); request.setValidTo(LocalDateTime.of(2027, 10, 1, 0, 0));
        withSyntheticAdmin(() -> context.getBean(M1MasterDataAdminController.class).publishListing(request));
    }
    private void changeStatusThroughController(BuyerListingDO listing, BuyerListingStatus target) {
        var request = new M1MasterDataAdminController.ChangeListingStatusReqVO();
        request.setListingNo(listing.getListingNo()); request.setExpectedListingVersion(listing.getVersion()); request.setTargetStatus(target.name());
        context.getBean(M1MasterDataAdminController.class).changeListingStatus(request);
    }
    private List<String> selectThroughController(String buyer, String... skus) {
        var request = new BuyerAdminController.AddBuyerProductsReqVO();
        request.setItems(Arrays.stream(skus).map(sku -> {
            var item = new BuyerAdminController.BuyerProductItemReqVO(); item.setPlatformSkuCode(sku); return item;
        }).toList());
        return context.getBean(BuyerAdminController.class).addProducts(buyer, request).getData();
    }
    /** 隔离管理员身份，真实Controller取非空operator；不调用外部权限/会员服务。 */
    private static <T> T withSyntheticAdmin(java.util.function.Supplier<T> action) {
        var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var original = security.getAuthentication();
        var user = new com.aifuli.framework.security.core.LoginUser(); user.setId(1L);
        user.setUserType(com.aifuli.framework.common.enums.UserTypeEnum.ADMIN.getValue());
        user.setTenantId(1L); user.setVisitTenantId(2L);
        security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null, List.of()));
        try { return action.get(); } finally { security.setAuthentication(original); }
    }
    private String newBuyer() {
        int n = buyers.incrementAndGet();
        return context.getBean(BuyerAdminCommandService.class).create(createCommand("桥接" + n, "测试企业" + n,
                String.format("91110000000000%03dX", n), 1, null, 0L));
    }
    private long count(String table, String buyer) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE buyer_id=(SELECT id FROM sc_buyer WHERE buyer_code=?)", Long.class, buyer);
    }
    @Configuration(proxyBeanMethods = false)
    @Import({BuyerAdminIntegrationTest.TestConfiguration.class, BuyerAdminController.class,
            M1MasterDataAdminController.class, BuyerCommandService.class, BuyerListingPublicationService.class,
            OutboxEventRepository.class,
            com.aifuli.module.supplychain.routing.application.SupplierRoutingApplicationService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.CurrentSupplyEligibilityReader.class})
    static class Config { }
    private static void seedCatalog(ProductCatalogCommandService catalog) {
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "BRD_001", "影院品牌", null, null, 0, 1L));
        catalog.changeBrandStatus("BRD_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_001", "权益商品", null, 0, 1L));
        catalog.changeCategoryStatus("CAT_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_001_001", "卡券", "CAT_001", 0, 1L));
        catalog.changeCategoryStatus("CAT_001_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_002_001", "电影快充", "CAT_001_001", 0, 1L));
        catalog.changeCategoryStatus("CAT_002_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "SPU_001", "BRD_001", "CAT_002_001",
                "MOVIE", "MOVIE", "MOVIE_TICKET",
                "电影票", null, null, null, 1L));
        catalog.changeSpuStatus("SPU_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "SKU_001_100", "SPU_001", "电影票 100", FulfillmentType.DIRECT_RECHARGE.name(),
                10_000L, Money.CURRENCY_CNY, 1L));
        catalog.changeSkuStatus("SKU_001_100", CatalogEntityStatus.ACTIVE, 1L);
    }

    private static CreateBuyerCommand createCommand(String name, String legalEntity, String creditCode,
                                                    Integer level, String parentBuyerCode, Long opening) {
        return createCommand(name, legalEntity, creditCode, level, parentBuyerCode, opening, List.of());
    }

    private static CreateBuyerCommand createCommand(String name, String legalEntity, String creditCode,
                                                    Integer level, String parentBuyerCode, Long opening,
                                                    List<String> cooperationModes) {
        return new CreateBuyerCommand(
                name,
                List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                parentBuyerCode,
                level,
                "ENTERPRISE",
                null,
                "MONTHLY",
                7,
                cooperationModes,
                List.of(),
                null,
                null,
                legalEntity,
                creditCode,
                "李四",
                "13900139000",
                "上海市浦东新区",
                "测试银行",
                "6222021234567890",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                opening,
                1L,
                "buyer-admin-it");
    }

}
