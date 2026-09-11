package com.aifuli.module.supplychain.tradecore.catalog;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.catalog.application.BrandAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSkuLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSpuCommand;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
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
import org.testcontainers.containers.MySQLContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品中心核心链路真实 MySQL 回归。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#6-测试策略}。
 */
class ProductCenterIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    @BeforeAll
    static void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void createsCompositeProductAndPreservesIndependentSkuStatusAcrossSpuSwitches() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BrandAdminService brandService = context.getBean(BrandAdminService.class);
            CategoryCommandService categoryService = context.getBean(CategoryCommandService.class);
            ProductCatalogCommandService commandService = context.getBean(ProductCatalogCommandService.class);
            ProductCatalogQueryService queryService = context.getBean(ProductCatalogQueryService.class);
            BrandMapper brandMapper = context.getBean(BrandMapper.class);

            String brandCode = brandService.create(new BrandAdminService.CreateCommand(
                    "PC_BRAND", "商品中心测试品牌", null, null, 0,
                    CatalogEntityStatus.ACTIVE.getCode(), 1L));
            String categoryCode = categoryService.create(new CategoryCommandService.CreateCommand(
                    "PC_CATEGORY", "商品中心测试分类", null, 0, (short) 1,
                    CatalogEntityStatus.ACTIVE.getCode(), 1L));
            Long brandId = brandMapper.selectByCode(brandCode).getId();
            Long categoryId = categoryService.detail(categoryCode).getId();

            String spuCode = commandService.createProduct(new SaveSpuCommand(
                    "PC_SPU", "商品中心虚拟直充", "复合保存回归", brandId, categoryId, List.of(),
                    "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", null, List.of(), "测试说明",
                    "测试详情", "VIRTUAL", 1L, 100L, CatalogEntityStatus.ACTIVE.getCode(),
                    List.of(new SaveSkuLine("PC_SKU_100", "商品中心直充100元", "DIRECT_RECHARGE",
                            10_000L, 10_000L, 9_900L, 1L, 10L,
                            "DIRECT_RECHARGE_TARGET", 1, "DIRECT_RECHARGE_RESULT", 1,
                            CatalogEntityStatus.ACTIVE.getCode())), 1L));

            ProductCatalogQueryService.SpuDetail created = queryService.detail(spuCode);
            assertThat(created.skus()).singleElement().satisfies(sku -> {
                assertThat(sku.skuCode()).isEqualTo("PC_SKU_100");
                assertThat(sku.effectiveSellable()).isTrue();
            });

            commandService.changeSkuStatus("PC_SKU_100", CatalogEntityStatus.INACTIVE, 1L);
            commandService.changeSpuStatus(spuCode, CatalogEntityStatus.INACTIVE, 1L);
            commandService.changeSpuStatus(spuCode, CatalogEntityStatus.ACTIVE, 1L);
            assertThat(queryService.detail(spuCode).skus()).singleElement().satisfies(sku -> {
                assertThat(sku.status()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
                assertThat(sku.effectiveSellable()).isFalse();
            });

            assertThatThrownBy(() -> brandService.changeStatus(
                    brandCode, CatalogEntityStatus.ARCHIVED, 1L))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private ConfigurableApplicationContext applicationContext() {
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
            ProductCatalogCommandService.class, ProductCatalogQueryService.class,
            ProductCenterTestConfiguration.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
