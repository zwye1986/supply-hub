package com.aifuli.module.supplychain.tradecore.catalog;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.catalog.application.BrandAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductTypeAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxRateCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_DISABLE_FORBIDDEN_HAS_PRODUCTS;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_INACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 MySQL/Liquibase 下证明禁用口径、编辑原子性，以及真实创建 SPU 与禁用类型的双向竞争。
 * 所有夹具使用独立容器和真实应用服务，不接入共享业务库。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#441-已关联平台商品的类型禁止禁用2026-10-23}。
 */
@Timeout(30)
class ProductTypeDisableIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private static ConfigurableApplicationContext context;
    private static ProductTypeAdminService service;
    private static ProductCatalogCommandService products;
    private static PlatformSpuMapper spus;
    private static ProductTypeMapper types;
    private static TransactionTemplate transaction;
    private static String brandCode;
    private static String categoryCode;

    @BeforeAll
    static void start() {
        MYSQL.start();
        context = new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username", MYSQL.getUsername(),
                        "aifuli.supply-chain.datasource.password", MYSQL.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"))
                .run();
        service = context.getBean(ProductTypeAdminService.class);
        products = context.getBean(ProductCatalogCommandService.class);
        spus = context.getBean(PlatformSpuMapper.class);
        types = context.getBean(ProductTypeMapper.class);
        transaction = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        brandCode = context.getBean(BrandAdminService.class).create(new BrandAdminService.CreateCommand(
                null, "类型禁用回归品牌", null, null, null, null, 0, CatalogEntityStatus.ACTIVE.getCode(), 1L));
        categoryCode = context.getBean(CategoryCommandService.class).create(new CategoryCommandService.CreateCommand(
                null, "类型禁用回归分类", null, List.of("EQUITY_PRODUCT"), null, null,
                0, (short) 1, CatalogEntityStatus.ACTIVE.getCode(), 1L));
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    private String createType() {
        return service.create(new ProductTypeAdminService.CreateCommand(null, "禁用回归" + java.util.UUID.randomUUID(),
                "https://example.com/icon.png", 1, CatalogEntityStatus.ACTIVE.getCode(), "原描述", "原备注", 1L));
    }

    private String createSpu(String typeCode) {
        return products.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                null, brandCode, categoryCode, "EQUITY", typeCode, "VIRTUAL_CARD", "类型回归商品", null, null, null, 1L));
    }

    private void disable(String typeCode, boolean throughEdit) {
        if (throughEdit) {
            service.update(typeCode, new ProductTypeAdminService.UpdateCommand("编辑新名称" + typeCode, "https://example.com/new.png",
                    20, CatalogEntityStatus.INACTIVE.getCode(), "新描述", "新备注", 2L));
        } else {
            service.changeStatus(typeCode, CatalogEntityStatus.INACTIVE, 2L);
        }
    }

    @ParameterizedTest
    @CsvSource({"true,0", "true,10", "true,20", "false,0", "false,10", "false,20"})
    void anyUnarchivedSpuWithoutSkuRejectsEntireEdit(boolean throughEdit, short spuStatus) {
        String code = createType();
        PlatformSpuDO spu = spus.selectByCode(createSpu(code));
        spu.setStatus(spuStatus);
        spus.updateById(spu);
        var original = types.selectByCode(code);

        assertThatThrownBy(() -> disable(code, throughEdit)).isInstanceOf(ServiceException.class)
                .hasMessage(PRODUCT_TYPE_DISABLE_FORBIDDEN_HAS_PRODUCTS.getMsg());

        assertThat(types.selectByCode(code)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void archivedSpuAndOtherTypesSpuDoNotBlockDisabling(boolean throughEdit) {
        String code = createType();
        PlatformSpuDO archived = spus.selectByCode(createSpu(code));
        archived.setStatus(CatalogEntityStatus.ARCHIVED.getCode());
        spus.updateById(archived);
        createSpu(createType());

        disable(code, throughEdit);

        assertThat(types.selectByCode(code).getStatus()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void creatingFirstMakesWaitingDisableRejectAfterCommit(boolean throughEdit) throws Exception {
        String code = createType();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> waitingDisable = transaction.execute(status -> {
                createSpu(code);
                CountDownLatch started = new CountDownLatch(1);
                Future<?> result = executor.submit(() -> {
                    started.countDown();
                    disable(code, throughEdit);
                });
                assertBlocked(started, result);
                return result;
            });

            assertThatThrownBy(() -> waitingDisable.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ServiceException.class)
                    .hasRootCauseMessage(PRODUCT_TYPE_DISABLE_FORBIDDEN_HAS_PRODUCTS.getMsg());
            assertThat(types.selectByCode(code).getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void disablingFirstMakesWaitingCreateRejectAfterCommit(boolean throughEdit) throws Exception {
        String code = createType();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> waitingCreate = transaction.execute(status -> {
                disable(code, throughEdit);
                CountDownLatch started = new CountDownLatch(1);
                Future<?> result = executor.submit(() -> {
                    started.countDown();
                    createSpu(code);
                });
                assertBlocked(started, result);
                return result;
            });

            assertThatThrownBy(() -> waitingCreate.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ServiceException.class).hasRootCauseMessage(PRODUCT_TYPE_INACTIVE.getMsg());
            assertThat(service.detail(code).spuCount()).isZero();
            assertThat(types.selectByCode(code).getStatus()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
        }
    }

    private static void assertBlocked(CountDownLatch started, Future<?> future) {
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            ProductTypeAdminService.class, ProductCatalogCommandService.class, GenericBatchPricingService.class,
            ProductCenterTestConfiguration.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        TaxRateDictionaryAdapter taxRateDictionaryAdapter() {
            var adapter = mock(TaxRateDictionaryAdapter.class);
            when(adapter.requireEnabled(false, PricingTaxRateCodes.NON_TAXABLE))
                    .thenReturn(new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税"));
            return adapter;
        }
    }
}
