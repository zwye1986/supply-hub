package com.aifuli.module.supplychain.tradecore.catalog;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.interfaceapi.admin.CategoryAdminController;
import com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.catalog.application.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实 MySQL/Liquibase/Mapper/事务下验证子树统计、HTTP 错误渲染及双向并发保护。
 * 设计见 {@code docs/superpowers/specs/2026-10-22-category-subtree-product-guard-design.md#5-实施与验证}。
 */
class CategoryProductGuardIntegrationTest {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    static ConfigurableApplicationContext context;
    static CategoryCommandService categories;
    static ProductCatalogCommandService products;
    static CategoryQueryService queries;
    static PlatformSpuMapper spus;
    static TransactionTemplate tx;
    static String brand;
    static Long brandId;
    static MockMvc mvc;
    static final AtomicInteger sequence = new AtomicInteger();
    static final DeterministicSqlInterceptor barriers = new DeterministicSqlInterceptor();

    @BeforeAll static void start() {
        MYSQL.start();
        context = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username", MYSQL.getUsername(),
                        "aifuli.supply-chain.datasource.password", MYSQL.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"))
                .run();
        context.getBean("supplyChainSqlSessionFactory", SqlSessionFactory.class)
                .getConfiguration().addInterceptor(barriers);
        categories = context.getBean(CategoryCommandService.class);
        products = context.getBean(ProductCatalogCommandService.class);
        queries = context.getBean(CategoryQueryService.class);
        spus = context.getBean(PlatformSpuMapper.class);
        tx = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        brand = context.getBean(BrandAdminService.class).create(new BrandAdminService.CreateCommand(
                null, "分类保护测试品牌", null, null, null, null, 0, CatalogEntityStatus.ACTIVE.getCode(), 1L));
        brandId = context.getBean(BrandMapper.class).selectByCode(brand).getId();
        mvc = MockMvcBuilders.standaloneSetup(new CategoryAdminController(queries, categories))
                .setControllerAdvice(new GlobalExceptionHandler("supply-chain-server", null)).build();
    }

    @AfterEach void clearBarrier() { barriers.reset(); }
    @AfterAll static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    static String category(String parent) {
        return categories.create(new CategoryCommandService.CreateCommand(null,
                "分类保护" + sequence.incrementAndGet(), "EQUITY_PRODUCT", null, parent,
                0, (short) 1, CatalogEntityStatus.ACTIVE.getCode(), 1L));
    }

    static String createSpu(String category) {
        return products.createSpu(new ProductCatalogCommandService.CreateSpuCommand(null, brand, category,
                "EQUITY", "EQUITY_PRODUCT", category, "分类保护商品" + sequence.incrementAndGet(),
                null, null, null, 1L));
    }

    @Test void realSubtreeCountsAndHttpErrorsPreserveExistingFrontendContract() throws Exception {
        String root = category(null);
        String child = category(root);
        String leaf = category(child);
        String spu = createSpu(leaf);
        long id = categories.detail(leaf).getId();
        var row = spus.selectByCode(spu);
        row.setSecondaryCategoryIds(List.of(id, id));
        spus.updateById(row);
        mvc.perform(get("/supply-chain/categories/management-tree").param("keyword", root))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].categoryCode").value(root))
                .andExpect(jsonPath("$.data[0].productCount").value(1));
        mvc.perform(put("/supply-chain/categories/" + root + "/status")
                        .contentType("application/json").content("{\"targetStatus\":" + CatalogEntityStatus.INACTIVE.getCode() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(CATEGORY_DISABLE_HAS_PRODUCTS.getCode()))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString(leaf)));
        mvc.perform(delete("/supply-chain/categories/" + root))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(CATEGORY_HAS_PRODUCTS.getCode()))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString(root)));
        assertThat(categories.detail(root).getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        row = spus.selectByCode(spu);
        row.setStatus(CatalogEntityStatus.ARCHIVED.getCode());
        spus.updateById(row);
        categories.changeStatus(root, CatalogEntityStatus.INACTIVE, 1L);
        assertThatThrownBy(() -> categories.delete(root, 1L)).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(CATEGORY_HAS_CHILDREN.getCode());
    }

    @Test void bindingCommitsFirstThenWaitingDisableSeesNewProduct() throws Exception {
        String root = category(null);
        String child = category(root);
        CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> tx.executeWithoutResult(s -> {
                createSpu(child);
                inserted.countDown();
                await(release);
            }));
            await(inserted);
            CountDownLatch attempting = onNextCategoryLock();
            Future<?> disabling = pool.submit(() -> categories.changeStatus(root, CatalogEntityStatus.INACTIVE, 1L));
            await(attempting);
            assertThatThrownBy(() -> disabling.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            writer.get(15, TimeUnit.SECONDS);
            assertThatThrownBy(() -> disabling.get(15, TimeUnit.SECONDS)).hasCauseInstanceOf(ServiceException.class)
                    .satisfies(e -> assertThat(((ServiceException) e.getCause()).getCode()).isEqualTo(CATEGORY_DISABLE_HAS_PRODUCTS.getCode()));
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void disableCommitsFirstThenWaitingCompatibilityBindingRejectsDisabledAncestor() throws Exception {
        String root = category(null), child = category(root);
        CountDownLatch disabled = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> tx.executeWithoutResult(s -> {
                categories.changeStatus(root, CatalogEntityStatus.INACTIVE, 1L);
                disabled.countDown(); await(release);
            }));
            await(disabled);
            CountDownLatch attempting = onNextCategoryLock();
            Future<?> binding = pool.submit(() -> createSpu(child));
            await(attempting);
            assertThatThrownBy(() -> binding.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); writer.get(15, TimeUnit.SECONDS);
            assertThatThrownBy(() -> binding.get(15, TimeUnit.SECONDS)).hasCauseInstanceOf(ServiceException.class)
                    .satisfies(e -> assertThat(((ServiceException) e.getCause()).getCode()).isEqualTo(CATEGORY_ANCESTOR_UNAVAILABLE.getCode()));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void compositeCreateAndUpdateRejectDisabledAncestorAndKeepExistingAssociation() {
        String disabledRoot = category(null), target = category(disabledRoot), source = category(null);
        String existing = createSpu(source);
        categories.changeStatus(disabledRoot, CatalogEntityStatus.INACTIVE, 1L);
        var command = productCommand(target);
        assertThatThrownBy(() -> products.createProduct(command)).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(CATEGORY_ANCESTOR_UNAVAILABLE.getCode());
        assertThatThrownBy(() -> products.updateProduct(existing, command)).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(CATEGORY_ANCESTOR_UNAVAILABLE.getCode());
        assertThat(spus.selectByCode(existing).getPrimaryCategoryId()).isEqualTo(categories.detail(source).getId());
    }

    @Test void deleteCommitsFirstThenWaitingBindingSeesArchivedTarget() throws Exception {
        String leaf = category(null);
        var command = productCommand(leaf);
        CountDownLatch deleted = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> tx.executeWithoutResult(s -> {
                categories.delete(leaf, 1L); deleted.countDown(); await(release);
            }));
            await(deleted);
            CountDownLatch attempting = onNextCategoryLock();
            Future<?> binding = pool.submit(() -> products.createProduct(command));
            await(attempting);
            assertThatThrownBy(() -> binding.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); writer.get(15, TimeUnit.SECONDS);
            assertThatThrownBy(() -> binding.get(15, TimeUnit.SECONDS)).hasCauseInstanceOf(ServiceException.class)
                    .satisfies(e -> assertThat(((ServiceException) e.getCause()).getCode()).isEqualTo(CATEGORY_NOT_FOUND.getCode()));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    static ProductCatalogCommandService.SaveSpuCommand productCommand(String category) {
        return new ProductCatalogCommandService.SaveSpuCommand(null, "分类绑定回归", null, brandId,
                categories.detail(category).getId(), List.of(), "EQUITY", "EQUITY_PRODUCT", category,
                null, List.of(), null, null, "VIRTUAL", 1L, 100L, CatalogEntityStatus.ACTIVE.getCode(),
                List.of(new ProductCatalogCommandService.SaveSkuLine(null, "绑定规格", "DIRECT_RECHARGE",
                        100L, 100L, 100L, 1L, 10L, CatalogEntityStatus.ACTIVE.getCode())), 1L);
    }

    @Test void rollbackReleasesClassificationAndAllowsDisable() {
        String root = category(null);
        tx.executeWithoutResult(status -> { createSpu(root); status.setRollbackOnly(); });
        categories.changeStatus(root, CatalogEntityStatus.INACTIVE, 1L);
        assertThat(categories.detail(root).getStatus()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
    }

    @Test void rejectsOuterRepeatableReadBeforeAnyWrite() {
        String root = category(null);
        TransactionTemplate rr = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> rr.executeWithoutResult(s -> categories.changeStatus(root, CatalogEntityStatus.INACTIVE, 1L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_COMMITTED");
        assertThat(categories.detail(root).getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
    }

    static CountDownLatch onNextCategoryLock() {
        CountDownLatch latch = new CountDownLatch(1);
        barriers.use(new DeterministicSqlInterceptor.Hook() {
            @Override public void before(String statement, String sql, Object params) {
                if (statement.endsWith("ProductCategoryMapper.selectList") && sql.contains("FOR UPDATE")) latch.countDown();
            }
        });
        return latch;
    }
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            ProductCatalogCommandService.class, ProductCenterTestConfiguration.class})
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean TaxRateDictionaryAdapter taxRateDictionaryAdapter() { return mock(TaxRateDictionaryAdapter.class); }
    }
}
