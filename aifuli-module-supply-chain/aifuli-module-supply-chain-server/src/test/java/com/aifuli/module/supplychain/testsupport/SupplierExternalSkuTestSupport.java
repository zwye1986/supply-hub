package com.aifuli.module.supplychain.testsupport;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCategoryCoverageService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.SuggestedSalePriceResolver;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.DraftLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferEligibilityService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import static org.mockito.Mockito.mock;

/**
 * 编码占用门禁：独占一次性MySQL，真实Liquibase、Mapper、RC事务与供应商锁，无外呼或共享库连接。
 * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#35-外部商品编码占用预检与管理端错误契约2026-10-22。
 */
public abstract class SupplierExternalSkuTestSupport {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    protected static ConfigurableApplicationContext context;
    protected static SupplierCooperativeProductService service;
    protected static SupplierOfferMapper offerMapper;
    protected static JdbcTemplate jdbc;

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
        service = context.getBean(SupplierCooperativeProductService.class);
        offerMapper = context.getBean(SupplierOfferMapper.class);
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (MYSQL.isRunning()) MYSQL.stop();
    }

    protected record Fixture(String supplierCode, Long supplierId, PlatformSkuDO first, PlatformSkuDO second) { }

    protected static Fixture fixture() {
        var now = LocalDateTime.now();
        String code = "EXT_SUP_" + SEQUENCE.incrementAndGet();
        var supplier = SupplierDO.builder().supplierCode(code).supplierName("编码占用回归")
                .shortName("编码回归").supplierType("STANDARD_SUPPLIER").status((short) 10)
                .version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierMapper.class).insert(supplier);
        context.getBean(SupplierSupplyScopeMapper.class).insert(SupplierSupplyScopeDO.builder()
                .supplierId(supplier.getId()).sellableCategory("EQUITY").productType("EQUITY_PRODUCT")
                .productClassification("VIRTUAL_CARD").maxQuantity(100L).minQuantity(1L)
                .version(0L).createdAt(now).updatedAt(now).build());
        var spu = PlatformSpuDO.builder().spuCode("EXT_SPU_" + SEQUENCE.incrementAndGet())
                .spuName("编码占用商品").brandId(1L).primaryCategoryId(1L)
                .sellableCategory("EQUITY").productType("EQUITY_PRODUCT").productClassification("VIRTUAL_CARD")
                .status((short) 10).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(PlatformSpuMapper.class).insert(spu);
        return new Fixture(code, supplier.getId(), sku(spu.getId()), sku(spu.getId()));
    }

    private static PlatformSkuDO sku(Long spuId) {
        var now = LocalDateTime.now();
        var sku = PlatformSkuDO.builder().skuCode("EXT_SKU_" + SEQUENCE.incrementAndGet()).spuId(spuId)
                .skuName("编码占用规格").fulfillmentType("DIRECT_RECHARGE").faceValue(10000L).currency("CNY")
                .marketPrice(10000L).suggestedSalePrice(10000L)
                .suggestedSalePriceRuleJson(com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.equalMarketPrice())
                .resultSchemaCode("STANDARD_RESULT_V1").resultSchemaVersion(1).status((short) 10)
                .version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(PlatformSkuMapper.class).insert(sku);
        return sku;
    }

    protected static DraftLine line(PlatformSkuDO sku, String external) {
        return new DraftLine(sku.getSkuCode(), "FIXED", 9800L, null, null, "API_DIRECT", 100L, 1L, external);
    }

    protected static long offerCount(Fixture f) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_offer WHERE supplier_id=?", Long.class, f.supplierId());
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, BusinessNoGenerator.class, UlidGenerator.class,
            ProductCategoryCoverageService.class, SuggestedSalePriceResolver.class, SupplierCooperativeProductService.class})
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean SupplierOfferPublicationService publication() { return mock(SupplierOfferPublicationService.class); }
        @Bean SupplierOfferEligibilityService eligibility() { return mock(SupplierOfferEligibilityService.class); }
    }
}
