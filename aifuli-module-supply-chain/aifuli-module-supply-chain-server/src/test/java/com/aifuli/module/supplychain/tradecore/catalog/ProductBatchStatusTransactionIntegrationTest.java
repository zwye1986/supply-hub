package com.aifuli.module.supplychain.tradecore.catalog;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.catalog.application.BrandAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.BatchStatusCommand;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.BatchStatusResult;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSkuLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSpuCommand;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityType;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxRateCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SPU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_BATCH_INPUT_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批量上下架「逐项独立事务」不变量的真实 MySQL 证明。
 * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status}（D1/D3）。
 * <p>单元测试 {@code ProductBatchStatusTest} 注入的是 mock 事务管理器，只能证明模板被配置成
 * {@code REQUIRES_NEW} 以及每项各调用一次 commit/rollback；<b>数据库层面「失败项不回滚已成功项」
 * 与「已成功项不随外层事务回滚」只能在此证明</b>，二者不可互相替代（AGENTS.md 规则 9）。
 * <p>全部用例共用一个容器与一个 Spring 上下文，避免每方法重启 Testcontainers 造成的容器churn；
 * 商品编码由发号器逐个分配，用例之间天然不重叠，故执行顺序无关。
 */
class ProductBatchStatusTransactionIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    private static ConfigurableApplicationContext context;
    private static ProductCatalogCommandService commandService;
    private static PlatformSpuMapper spuMapper;
    private static PlatformTransactionManager transactionManager;
    private static Long brandId;
    private static Long categoryId;

    /**
     * 库中必然不存在的 SPU 编码，用于制造 {@code PLATFORM_SPU_NOT_FOUND} 失败项。
     * <p>刻意不写成合法的 {@code SPU_NNN}：发号器按序占用号段，任何合法格式都可能与真实数据相撞；
     * 批量上下架路径只做「非空白 + 按编码查行」，不校验编码格式，故非法格式串反而更稳定。
     */
    private static final String ABSENT_SPU_CODE = "SPU_ABSENT_FROM_DB";

    private static final AtomicInteger SPU_SEQUENCE = new AtomicInteger();

    @BeforeAll
    static void startContainerAndContext() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
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
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    /** 建一个上架 SPU 带一个上架 SKU，作为批量下架的输入；返回发号器分配的 SPU 编码。 */
    private static String createActiveSpu() {
        int seq = SPU_SEQUENCE.incrementAndGet();
        return commandService.createProduct(new SaveSpuCommand(
                null, "批量事务回归商品" + seq, "批量事务回归", brandId, categoryId, List.of(),
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", null, List.of(), "测试说明",
                "测试详情", "VIRTUAL", 1L, 100L, CatalogEntityStatus.ACTIVE.getCode(),
                List.of(new SaveSkuLine(null, "批量事务回归规格" + seq, "DIRECT_RECHARGE",
                        10_000L, 12_000L, 9_900L, 1L, 10L,
                        CatalogEntityStatus.ACTIVE.getCode())),
                1L));
    }

    /**
     * D1 核心不变量：中间项失败只回滚它自己，先前已成功项保持已提交状态。
     * 断言落在数据库上而非仅落在回执上——回执可以由内存列表伪造，库中状态不能。
     */
    @Test
    void failedItemDoesNotRollBackAlreadyCommittedItems() {
        String first = createActiveSpu();
        String second = createActiveSpu();

        BatchStatusResult result = commandService.batchChangeStatus(new BatchStatusCommand(
                List.of(first, ABSENT_SPU_CODE, second), null,
                CatalogEntityStatus.INACTIVE.getCode(), null, 1L));

        assertThat(result.succeeded()).containsExactly(first, second);
        assertThat(result.failed()).singleElement().satisfies(item -> {
            assertThat(item.entityType()).isEqualTo(CatalogEntityType.SPU);
            assertThat(item.code()).isEqualTo(ABSENT_SPU_CODE);
            assertThat(item.errorCode()).isEqualTo(PLATFORM_SPU_NOT_FOUND.getCode());
        });
        // 回执不变量：成功 + 失败 == 入参条数
        assertThat(result.succeeded().size() + result.failed().size()).isEqualTo(3);

        assertThat(spuMapper.selectByCode(first).getStatus())
                .isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
        assertThat(spuMapper.selectByCode(second).getStatus())
                .isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
    }

    /**
     * D1 决定性用例：把批量调用放进一个随后失败回滚的<b>外层事务</b>。
     * <p>若各项事务为默认 {@code PROPAGATION_REQUIRED}，它们会并入外层事务：
     * 一方面成功项随外层回滚丢失，另一方面被捕获的 {@code ServiceException} 会把外层标记为
     * rollback-only 从而抛 {@code UnexpectedRollbackException}。
     * 只有 {@code REQUIRES_NEW} 能让成功项在外层回滚后仍然留在库中。
     */
    @Test
    void committedItemsSurviveOuterTransactionRollback() {
        String spuCode = createActiveSpu();
        List<BatchStatusResult> captured = new ArrayList<>();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            captured.add(commandService.batchChangeStatus(new BatchStatusCommand(
                    List.of(spuCode, ABSENT_SPU_CODE), null,
                    CatalogEntityStatus.INACTIVE.getCode(), null, 1L)));
            throw new IllegalStateException("外层事务在批量之后失败");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("外层事务在批量之后失败");

        assertThat(captured).singleElement().satisfies(result -> {
            assertThat(result.succeeded()).containsExactly(spuCode);
            assertThat(result.failed()).hasSize(1);
        });
        // 外层事务已回滚，成功项因 REQUIRES_NEW 已独立提交，不受影响。
        assertThat(spuMapper.selectByCode(spuCode).getStatus())
                .isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
    }

    /** D3：入参整体拒绝发生在任何事务开启之前，库中状态不得被改动。 */
    @Test
    void inputRejectionHappensBeforeAnyTransaction() {
        String spuCode = createActiveSpu();

        assertThatThrownBy(() -> commandService.batchChangeStatus(new BatchStatusCommand(
                List.of("  ", spuCode), null,
                CatalogEntityStatus.INACTIVE.getCode(), null, 1L)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_BATCH_INPUT_INVALID.getCode());

        assertThat(spuMapper.selectByCode(spuCode).getStatus())
                .isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
    }

    private static ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String username() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root") : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "") : MYSQL.getPassword();
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
