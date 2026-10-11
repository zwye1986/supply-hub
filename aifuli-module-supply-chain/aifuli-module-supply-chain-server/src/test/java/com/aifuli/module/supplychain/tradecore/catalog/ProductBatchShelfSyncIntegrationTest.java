package com.aifuli.module.supplychain.tradecore.catalog;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.catalog.application.BrandAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.BatchStatusCommand;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.BatchStatusResult;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSkuLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSpuCommand;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityType;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxRateCodes;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_BATCH_INPUT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CONCURRENT_UPDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同步上架组内原子、组间独立及成员锁顺序的真实 MySQL/Liquibase 证明。
 * 设计见 {@code docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#4-分组事务与并发}。
 * 本类固定启动独立 MySQL 8.4 容器，不连接外部业务数据库；真实 Mapper 与事务仅在指定边界加入时序/更新冲突钩子。
 * 商品、品牌与分类由真实发号器分配，用例数据天然不重叠，执行顺序无关。
 */
class ProductBatchShelfSyncIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain")
            .withCommand("--log-bin-trust-function-creators=1");

    private static ConfigurableApplicationContext context;
    private static ProductCatalogCommandService commandService;
    private static PlatformSpuMapper spuMapper;
    private static PlatformTransactionManager transactionManager;
    private static Long brandId;
    private static Long categoryId;

    private static final String SUPPLIER_TYPE_STANDARD = "STANDARD_SUPPLIER";
    private static final AtomicInteger SPU_SEQUENCE = new AtomicInteger();

    @BeforeAll
    static void startContainerAndContext() {
        MYSQL.start();
        context = applicationContext();
        commandService = context.getBean(ProductCatalogCommandService.class);
        spuMapper = context.getBean(PlatformSpuMapper.class);
        transactionManager = context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class);

        // 品牌与分类编码同样交给发号器分配：手写常量既要满足 PREFIX_NNN 格式又要避开已占用号段，
        // 对本用例要证明的事务边界毫无价值。
        BrandAdminService brandService = context.getBean(BrandAdminService.class);
        CategoryCommandService categoryService = context.getBean(CategoryCommandService.class);
        String brandCode = brandService.create(new BrandAdminService.CreateCommand(
                null, "批量事务回归品牌", "批量事务", null, null,
                "批量上下架逐项独立事务回归", 0, CatalogEntityStatus.ACTIVE.getCode(), 1L));
        brandId = context.getBean(BrandMapper.class).selectByCode(brandCode).getId();
        String categoryCode = categoryService.create(new CategoryCommandService.CreateCommand(
                null, "批量事务回归分类", null, List.of("EQUITY_PRODUCT"),
                "批量上下架逐项独立事务回归", null, 0, (short) 1,
                CatalogEntityStatus.ACTIVE.getCode(), 1L));
        categoryId = categoryService.detail(categoryCode).getId();
    }

    @AfterAll
    static void stopContextAndContainer() {
        if (context != null) {
            context.close();
        }
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    private static PlatformSkuMapper skuMapper() {
        return context.getBean(PlatformSkuMapper.class);
    }

    private static List<PlatformSkuDO> members(String code) {
        return skuMapper().selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .eq(PlatformSkuDO::getSpuId, spuMapper.selectByCode(code).getId())
                .orderByAsc(PlatformSkuDO::getId));
    }

    private static String createDraftSpu(int count) {
        List<SaveSkuLine> lines = IntStream.range(0, count).mapToObj(i -> new SaveSkuLine(
                null, "同步规格" + i, "DIRECT_RECHARGE", 10_000L, 12_000L, 9_900L, 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode())).toList();
        return commandService.createProduct(new SaveSpuCommand(null, "同步商品" + SPU_SEQUENCE.incrementAndGet(),
                null, brandId, categoryId, List.of(), "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", null, List.of(),
                null, null, "VIRTUAL", 1L, 100L, CatalogEntityStatus.DRAFT.getCode(), lines, 1L));
    }

    private static BatchStatusCommand sync(String... codes) {
        return new BatchStatusCommand(List.of(codes), List.of(), CatalogEntityStatus.ACTIVE.getCode(), null, 1L, true);
    }

    @Test
    void activatesDraftInactiveAndActiveMembersButLeavesArchivedUntouched() {
        String code = createDraftSpu(4);
        var rows = members(code);
        commandService.changeSpuStatus(code, CatalogEntityStatus.ACTIVE, 1L);
        commandService.changeSkuStatus(rows.get(1).getSkuCode(), CatalogEntityStatus.ACTIVE, 1L);
        commandService.changeSkuStatus(rows.get(1).getSkuCode(), CatalogEntityStatus.INACTIVE, 1L);
        commandService.changeSkuStatus(rows.get(2).getSkuCode(), CatalogEntityStatus.ACTIVE, 1L);
        commandService.changeSkuStatus(rows.get(3).getSkuCode(), CatalogEntityStatus.ARCHIVED, 1L);
        BatchStatusResult result = commandService.batchChangeStatus(sync(code));
        assertThat(result.succeeded()).containsExactly(code);
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.succeeded()).isTrue();
            assertThat(group.skuCodes()).containsExactlyElementsOf(rows.subList(0, 3).stream().map(row -> row.getSkuCode()).toList());
        });
        assertThat(members(code).subList(0, 3)).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode()));
        assertThat(skuMapper().selectByCode(rows.get(3).getSkuCode()).getStatus()).isEqualTo(CatalogEntityStatus.ARCHIVED.getCode());
    }

    @Test
    void invalidSchemaRejectsWholeGroupAndKeepsOtherGroupCommitted() {
        String good = createDraftSpu(2);
        String bad = createDraftSpu(2);
        var badRows = members(bad);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            var invalid = skuMapper().selectByCodeForUpdate(badRows.get(1).getSkuCode());
            invalid.setResultSchemaCode("DEFAULT");
            skuMapper().updateById(invalid);
        });
        BatchStatusResult result = commandService.batchChangeStatus(sync(good, bad));
        assertThat(result.succeeded()).containsExactly(good);
        assertThat(result.failed()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo(bad);
            assertThat(item.failedEntityType()).isEqualTo(CatalogEntityType.SKU);
            assertThat(item.failedEntityCode()).isEqualTo(badRows.get(1).getSkuCode());
        });
        assertThat(spuMapper.selectByCode(good).getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        assertThat(members(good)).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode()));
        assertDraftGroup(bad);
    }

    /** 故障发生在父与第一个SKU已经UPDATE之后，真实MySQL事务必须回滚整组。 */
    @Test
    void databaseFailureAfterFirstSkuWriteRollsBackGroupAndPropagatesCause() {
        String good = createDraftSpu(1);
        String bad = createDraftSpu(2);
        long failId = members(bad).get(1).getId();
        var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        String trigger = "sc_sync_failure_" + failId;
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON sc_platform_sku FOR EACH ROW BEGIN IF NEW.id = "
                + failId + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='同步第二SKU写入故障'; END IF; END");
        try {
            assertThatThrownBy(() -> commandService.batchChangeStatus(sync(good, bad)))
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("同步第二SKU写入故障");
            assertDraftGroup(bad);
            assertThat(spuMapper.selectByCode(good).getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
            assertThat(members(good).getFirst().getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
    }

    @Test
    void synchronizedShelvingClearsPersistedUnshelveMode() {
        String code = createDraftSpu(1);
        commandService.changeSpuStatus(code, CatalogEntityStatus.ACTIVE, 1L);
        commandService.changeSpuStatus(code, CatalogEntityStatus.INACTIVE, 1L);
        assertThat(spuMapper.selectByCode(code).getUnshelveMode())
                .isEqualTo(com.aifuli.module.supplychain.tradecore.catalog.domain.UnshelveMode.MANUAL_UNSHELVE.getCode());
        assertThat(commandService.batchChangeStatus(sync(code)).succeeded()).containsExactly(code);
        assertThat(spuMapper.selectByCode(code).getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        assertThat(spuMapper.selectByCode(code).getUnshelveMode()).isNull();
    }

    @Test
    void reversedTaxBatchAndSynchronizedShelvingBothCommitWithoutDeadlock() throws Exception {
        assertReverseBatchAndSyncBothCommit(false);
    }

    @Test
    void reversedManualPlatformPricingAndSynchronizedShelvingBothCommitWithoutDeadlock() throws Exception {
        assertReverseBatchAndSyncBothCommit(true);
    }

    /**
     * 真实Mapper首次取得子锁后暂停反序writer，随后让同步SELECT进入等待，再放行writer第二条锁。
     * 修复前writer持B、sync持A形成环；修复后writer先持A，sync仅等待，writer顺序完成后双方均提交。
     */
    private static void assertReverseBatchAndSyncBothCommit(boolean pricing) throws Exception {
        String code = createDraftSpu(2);
        commandService.batchChangeStatus(sync(code));
        List<String> reverse = members(code).reversed().stream().map(PlatformSkuDO::getSkuCode).toList();
        CountDownLatch writerFirstLock = new CountDownLatch(1);
        CountDownLatch syncLockAttempt = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        MapperInterceptor writerHook = (method, args, invoke) -> {
            Object result = invoke.call();
            if ((method.getName().equals("selectByCodeForUpdate") || method.getName().equals("selectByIdForUpdate"))
                    && paused.compareAndSet(false, true)) {
                writerFirstLock.countDown();
                assertThat(releaseWriter.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        };
        var syncService = withSkuInterceptor((method, args, invoke) -> {
            if (method.getName().equals("selectByIdForUpdate")) syncLockAttempt.countDown();
            return invoke.call();
        });
        Callable<Void> mutation;
        if (pricing) {
            var pricingService = withSkuInterceptor(GenericBatchPricingService.class, writerHook);
            mutation = () -> {
                pricingService.savePlatform(reverse,
                        com.aifuli.module.supplychain.tradecore.catalog.pricing.PlatformDefaultPricingPolicy.create(), 1L);
                return null;
            };
        } else {
            var taxService = withSkuInterceptor(writerHook);
            mutation = () -> {
                taxService.batchUpdateSkuTaxMetadata(new ProductCatalogCommandService.SkuTaxMetadataBatchCommand(
                        reverse, true, new java.math.BigDecimal("0.0600"), 1L));
                return null;
            };
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                try { mutation.call(); }
                catch (RuntimeException failure) { throw failure; }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }));
            assertThat(writerFirstLock.await(3, TimeUnit.SECONDS)).isTrue();
            Future<BatchStatusResult> sync = pool.submit(() -> syncService.batchChangeStatus(sync(code)));
            assertThat(syncLockAttempt.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> sync.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseWriter.countDown();
            org.assertj.core.api.Assertions.assertThatCode(() -> {
                writer.get(10, TimeUnit.SECONDS);
                assertThat(sync.get(10, TimeUnit.SECONDS).succeeded()).containsExactly(code);
            }).doesNotThrowAnyException();
            assertThat(members(code)).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode()));
            if (!pricing) {
                assertThat(members(code)).allSatisfy(row -> {
                    assertThat(row.getTaxIncluded()).isTrue();
                    assertThat(row.getTaxRate()).isEqualByComparingTo("0.0600");
                });
            }
        } finally {
            releaseWriter.countDown();
            pool.shutdownNow();
        }
    }

    /** Mapper报告第二规格更新0行时，已写父/首SKU在真实事务中回滚，失败回执定位第二SKU。 */
    @Test
    void concurrentUpdateBusinessFailureAfterFirstWriteRollsBackWholeGroup() throws Exception {
        String first = createDraftSpu(1);
        String failed = createDraftSpu(2);
        String failedSku = members(failed).get(1).getSkuCode();
        ProductCatalogCommandService wrapped = withSkuInterceptor((method, args, invoke) -> {
            if (method.getName().equals("updateById") && args[0] instanceof PlatformSkuDO row
                    && failedSku.equals(row.getSkuCode())) return 0;
            return invoke.call();
        });
        BatchStatusResult result = wrapped.batchChangeStatus(sync(first, failed));
        assertThat(result.succeeded()).containsExactly(first);
        assertThat(result.failed()).singleElement().satisfies(item -> {
            assertThat(item.failedEntityCode()).isEqualTo(failedSku);
            assertThat(item.errorCode()).isEqualTo(PRODUCT_CONCURRENT_UPDATE.getCode());
        });
        assertDraftGroup(failed);
        assertThat(spuMapper.selectByCode(first).getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        assertThat(members(first).getFirst().getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
    }

    @Test
    void supplierSelectionFiltersBothLevelsBeforePagingAndRejectsConflictingStatus() {
        String keyword = "准入" + SPU_SEQUENCE.incrementAndGet();
        String draftParent = createDraftSpu(1);
        String inactiveParent = createDraftSpu(1);
        String draftSku = createDraftSpu(1);
        String ready = createDraftSpu(2);
        commandService.changeSkuStatus(members(draftParent).getFirst().getSkuCode(), CatalogEntityStatus.ACTIVE, 1L);
        commandService.changeSpuStatus(inactiveParent, CatalogEntityStatus.ACTIVE, 1L);
        commandService.changeSpuStatus(inactiveParent, CatalogEntityStatus.INACTIVE, 1L);
        commandService.changeSkuStatus(members(inactiveParent).getFirst().getSkuCode(), CatalogEntityStatus.ACTIVE, 1L);
        commandService.changeSpuStatus(draftSku, CatalogEntityStatus.ACTIVE, 1L);
        commandService.changeSpuStatus(ready, CatalogEntityStatus.ACTIVE, 1L);
        commandService.changeSkuStatus(members(ready).getFirst().getSkuCode(), CatalogEntityStatus.ACTIVE, 1L);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            for (String code : List.of(draftParent, inactiveParent, draftSku, ready)) {
                var parent = spuMapper.selectByCodeForUpdate(code);
                parent.setSpuName(keyword + code);
                spuMapper.updateById(parent);
            }
            var supplier = SupplierDO.builder()
                    .supplierCode(keyword).supplierName(keyword).shortName(keyword)
                    .supplierType(SUPPLIER_TYPE_STANDARD)
                    .status(SupplierStatus.ACTIVE.getCode())
                    .version(0L).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            context.getBean(SupplierMapper.class).insert(supplier);
        });
        var queries = context.getBean(ProductCatalogQueryService.class);
        var eligible = queries.managementPage(new ProductCatalogQueryService.ProductManagementPageQuery(
                null, keyword, null, null, null, null, null, null, null, null, null, null,
                keyword, null, null, 1, 1, null, true));
        assertThat(eligible.getTotal()).isEqualTo(1L);
        assertThat(eligible.getList()).singleElement().satisfies(parent -> {
            assertThat(parent.spuCode()).isEqualTo(ready);
            assertThat(parent.skus()).hasSize(1);
            assertThat(parent.skus().getFirst().status()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        });
        var conflicting = queries.managementPage(new ProductCatalogQueryService.ProductManagementPageQuery(
                null, keyword, null, null, null, CatalogEntityStatus.DRAFT.getCode(), null, null, null, null, null, null,
                keyword, null, null, 1, 20, null, true));
        assertThat(conflicting.getTotal()).isZero();
        assertThat(conflicting.getList()).isEmpty();
        var ordinary = queries.managementPage(new ProductCatalogQueryService.ProductManagementPageQuery(
                null, keyword, null, null, null, CatalogEntityStatus.ACTIVE.getCode(), null, null, null, null, null, null,
                null, null, null, 1, 20, null, false));
        assertThat(ordinary.getTotal()).isEqualTo(3L);
        assertThat(ordinary.getList()).extracting(ProductCatalogQueryService.ProductManagementSpuItem::spuCode)
                .containsExactlyInAnyOrder(draftParent, inactiveParent, ready);
    }

    @Test
    void expandedOverLimitRejectsAllGroupsWithoutAnyWrite() {
        String first = createDraftSpu(1);
        String over = createDraftSpu(198);
        assertThatThrownBy(() -> commandService.batchChangeStatus(sync(first, over)))
                .hasFieldOrPropertyWithValue("code", PRODUCT_BATCH_INPUT_INVALID.getCode());
        assertDraftGroup(first);
        assertDraftGroup(over);
    }

    @Test
    void synchronizedGroupCommitSurvivesOuterRollback() {
        String code = createDraftSpu(2);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            assertThat(commandService.batchChangeStatus(sync(code)).succeeded()).containsExactly(code);
            throw new IllegalStateException("外层回滚");
        })).hasMessage("外层回滚");
        assertThat(spuMapper.selectByCode(code).getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        assertThat(members(code)).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode()));
    }

    @Test
    void memberAddedAfterPlanningRejectsGroupInsteadOfOmittingNewSku() throws Exception {
        String code = createDraftSpu(1);
        AtomicBoolean injected = new AtomicBoolean();
        ProductCatalogCommandService wrapped = withSkuInterceptor((method, args, invoke) -> {
            Object result = invoke.call();
            if (method.getName().equals("selectList") && args[0] instanceof LambdaQueryWrapper<?> query
                    && !query.getSqlSegment().contains("FOR UPDATE") && injected.compareAndSet(false, true)) {
                commandService.createSku(new ProductCatalogCommandService.CreateSkuCommand(null, code, "并发新增规格", "DIRECT_RECHARGE", 100L, "CNY", 1L));
            }
            return result;
        });
        BatchStatusResult result = wrapped.batchChangeStatus(sync(code));
        assertThat(result.failed()).singleElement().satisfies(item -> assertThat(item.errorCode())
                .isEqualTo(PRODUCT_CONCURRENT_UPDATE.getCode()));
        assertThat(result.groups().getFirst().skuCodes()).hasSize(1);
        assertThat(members(code)).hasSize(2);
        assertDraftGroup(code);
    }

    @Test
    void memberArchivedAfterPlanningRejectsGroupAndPreservesArchivedState() throws Exception {
        String code = createDraftSpu(2);
        String archived = members(code).get(1).getSkuCode();
        AtomicBoolean injected = new AtomicBoolean();
        ProductCatalogCommandService wrapped = withSkuInterceptor((method, args, invoke) -> {
            Object result = invoke.call();
            if (method.getName().equals("selectList") && args[0] instanceof LambdaQueryWrapper<?> query
                    && !query.getSqlSegment().contains("FOR UPDATE") && injected.compareAndSet(false, true)) {
                commandService.changeSkuStatus(archived, CatalogEntityStatus.ARCHIVED, 1L);
            }
            return result;
        });
        assertThat(wrapped.batchChangeStatus(sync(code)).failed()).singleElement().satisfies(item -> assertThat(item.errorCode())
                .isEqualTo(PRODUCT_CONCURRENT_UPDATE.getCode()));
        assertThat(spuMapper.selectByCode(code).getStatus()).isEqualTo(CatalogEntityStatus.DRAFT.getCode());
        assertThat(members(code).getFirst().getStatus()).isEqualTo(CatalogEntityStatus.DRAFT.getCode());
        assertThat(skuMapper().selectByCode(archived).getStatus()).isEqualTo(CatalogEntityStatus.ARCHIVED.getCode());
    }

    /** 新增入口在同步已锁父行时等待；提交后新增草稿保持独立，下一次同步才处理它。 */
    @Test
    void concurrentSkuCreationWaitsForParentLockWithoutDeadlocking() throws Exception {
        String code = createDraftSpu(1);
        assertWriterWaitsForGroupCommit(code, () -> commandService.createSku(
                new ProductCatalogCommandService.CreateSkuCommand(null, code, "等待父锁规格", "DIRECT_RECHARGE", 100L, "CNY", 1L)));
        assertThat(members(code)).hasSize(2);
        assertThat(members(code).get(1).getStatus()).isEqualTo(CatalogEntityStatus.DRAFT.getCode());
    }

    @Test
    void concurrentSkuArchivalWaitsForParentLockWithoutDeadlocking() throws Exception {
        String code = createDraftSpu(1);
        String skuCode = members(code).getFirst().getSkuCode();
        assertWriterWaitsForGroupCommit(code, () -> {
            commandService.changeSkuStatus(skuCode, CatalogEntityStatus.ARCHIVED, 1L);
            return skuCode;
        });
        assertThat(spuMapper.selectByCode(code).getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        assertThat(skuMapper().selectByCode(skuCode).getStatus()).isEqualTo(CatalogEntityStatus.ARCHIVED.getCode());
    }

    @Test
    void concurrentCompositeEditWaitsForParentLockAndPreservesExistingSkuStatus() throws Exception {
        String code = createDraftSpu(1);
        String kept = members(code).getFirst().getSkuCode();
        SaveSpuCommand edit = new SaveSpuCommand(code, "并发编辑商品", null, brandId, categoryId, List.of(),
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", null, List.of(), null, null, "VIRTUAL", 1L, 100L, null,
                List.of(new SaveSkuLine(kept, "保留规格", "DIRECT_RECHARGE", 10_000L, 12_000L, 9_900L, 1L, 10L, null),
                        new SaveSkuLine(null, "新增规格", "DIRECT_RECHARGE", 10_000L, 12_000L, 9_900L, 1L, 10L,
                                CatalogEntityStatus.DRAFT.getCode())), 1L);
        assertWriterWaitsForGroupCommit(code, () -> {
            commandService.updateProduct(code, edit);
            return code;
        });
        assertThat(members(code)).hasSize(2);
        assertThat(skuMapper().selectByCode(kept).getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        assertThat(members(code).get(1).getStatus()).isEqualTo(CatalogEntityStatus.DRAFT.getCode());
    }

    private static void assertWriterWaitsForGroupCommit(String code, Callable<String> mutation) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<String>> writer = new AtomicReference<>();
        AtomicBoolean injected = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        try {
            ProductCatalogCommandService wrapped = withSkuInterceptor((method, args, invoke) -> {
                Object result = invoke.call();
                if (method.getName().equals("selectByIdForUpdate") && injected.compareAndSet(false, true)) {
                    writer.set(pool.submit(() -> {
                        started.countDown();
                        return mutation.call();
                    }));
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> writer.get().get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                }
                return result;
            });
            assertThat(wrapped.batchChangeStatus(sync(code)).succeeded()).containsExactly(code);
            assertThat(writer.get().get(10, TimeUnit.SECONDS)).isNotBlank();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertDraftGroup(String code) {
        assertThat(spuMapper.selectByCode(code).getStatus()).isEqualTo(CatalogEntityStatus.DRAFT.getCode());
        assertThat(members(code)).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(CatalogEntityStatus.DRAFT.getCode()));
    }

    @FunctionalInterface
    private interface MapperInterceptor {
        Object call(Method method, Object[] args, Callable<Object> invoke) throws Exception;
    }

    /** 只在真实Mapper边界设时序钩子；SQL、连接、事务、Liquibase均使用隔离MySQL容器。 */
    private static ProductCatalogCommandService withSkuInterceptor(MapperInterceptor interceptor) throws Exception {
        return withSkuInterceptor(ProductCatalogCommandService.class, interceptor);
    }

    private static <T> T withSkuInterceptor(Class<T> serviceType, MapperInterceptor interceptor) throws Exception {
        var mapper = skuMapper();
        Object proxy = Proxy.newProxyInstance(mapper.getClass().getClassLoader(),
                new Class<?>[]{PlatformSkuMapper.class},
                (target, method, args) -> interceptor.call(method, args, () -> {
                    try { return method.invoke(mapper, args); }
                    catch (InvocationTargetException wrapped) {
                        if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
                        throw wrapped;
                    }
                }));
        var constructor = serviceType.getConstructors()[0];
        Object[] dependencies = Arrays.stream(constructor.getParameterTypes())
                .map(type -> type == PlatformSkuMapper.class ? proxy : type.isInstance(context) ? context : context.getBean(type)).toArray();
        return serviceType.cast(constructor.newInstance(dependencies));
    }

    private static ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", MYSQL.getUsername());
        properties.put("aifuli.supply-chain.datasource.password", MYSQL.getPassword());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            ProductCatalogCommandService.class, ProductCatalogQueryService.class, CategoryQueryService.class,
            PlatformSkuQueryService.class, GenericBatchPricingService.class,
            ProductCenterTestConfiguration.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        TaxRateDictionaryAdapter taxRateDictionaryAdapter() {
            TaxRateDictionaryAdapter adapter = mock(TaxRateDictionaryAdapter.class);
            when(adapter.requireEnabled(false, PricingTaxRateCodes.NON_TAXABLE))
                    .thenReturn(new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税"));
            return adapter;
        }
    }
}
