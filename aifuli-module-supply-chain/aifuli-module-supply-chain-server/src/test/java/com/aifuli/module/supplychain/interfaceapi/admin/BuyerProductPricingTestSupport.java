package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.security.core.service.SecurityFrameworkService;
import com.aifuli.module.supplychain.tradecore.buyer.application.*;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.*;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.Mockito.*;

/**
 * 真实 MySQL/Liquibase、Spring 事务与 Mapper 共用夹具，供读取契约和保存往返门禁使用。
 * 不模拟定价服务、列表查询或持久化；税率字典外部 RPC 使用既有集成测试替身。
 * 设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#63-分销商品原始配置回显与无损编辑2026-10-14-修复。
 */
abstract class BuyerProductPricingTestSupport {
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain")
            .withCommand("--log-bin-trust-function-creators=1");
    protected ConfigurableApplicationContext context;
    protected JdbcTemplate jdbc;
    protected GenericBatchPricingService pricing;
    protected BuyerProductQueryService query;
    protected ObjectMapper mapper;
    protected long sequence;

    @BeforeAll
    void startPricingContext() {
        mysql.start();
        context = new SpringApplicationBuilder(Configuration.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.connector.allowed-adapter-types", "QNM_GAME_ORDER_V1",
                        "aifuli.supply-chain.datasource.url", mysql.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username", mysql.getUsername(),
                        "aifuli.supply-chain.datasource.password", mysql.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver",
                        "aifuli.supply-chain.catalog.system-operator-id", "9001"))
                .run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        pricing = context.getBean(GenericBatchPricingService.class);
        query = context.getBean(BuyerProductQueryService.class);
        mapper = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    void stopPricingContext() {
        if (context != null) context.close();
        mysql.stop();
    }

    protected Fixture fixture(Integer legacyThreshold) {
        LocalDateTime now = context.getBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class).now();
        String suffix = Long.toString(++sequence);
        String buyerCode = context.getBean(BuyerCommandService.class).createBuyer(
                new BuyerCommandService.CreateBuyerCommand(null, "往返买家" + suffix, "往返买家" + suffix,
                        null, null, "AIFULI_MALL", "roundtrip-" + suffix, now.minusMinutes(1), 1L));
        BuyerDO buyer = context.getBean(BuyerMapper.class).selectByBuyerCode(buyerCode);
        ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
        String brand = "BRD_%03d".formatted(sequence), category = "CAT_%03d".formatted(sequence);
        String spuCode = "SPU_%03d".formatted(sequence), skuCode = "SKU_%03d_100".formatted(sequence);
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(brand, brand, null, null, 0, 1L));
        catalog.changeBrandStatus(brand, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(category, category, null, 0, 1L));
        catalog.changeCategoryStatus(category, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(spuCode, brand, category,
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", spuCode, null, null, "不可自动撤销", 1L));
        catalog.changeSpuStatus(spuCode, CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(skuCode, spuCode, "往返SKU" + suffix,
                "DIRECT_RECHARGE", 10000L, "CNY", 1L));
        catalog.changeSkuStatus(skuCode, CatalogEntityStatus.ACTIVE, 1L);
        PlatformSkuDO sku = context.getBean(PlatformSkuMapper.class).selectByCode(skuCode);
        jdbc.update("UPDATE sc_platform_sku SET market_price=10000, suggested_sale_price=8800 WHERE id=?", sku.getId());
        pricing.initializePlatformDefaults(new PlatformSkuPricingInitializationEvent(List.of(skuCode), 1L));
        BuyerProductDO product = newProduct(buyer.getId(), sku.getId(), "ROUND_BPD_" + suffix, legacyThreshold, now);
        return new Fixture(buyer, product, sku);
    }

    protected BuyerProductDO newProduct(Long buyerId, Long skuId, String code, Integer threshold, LocalDateTime now) {
        BuyerProductDO product = BuyerProductDO.builder().buyerProductCode(code).buyerId(buyerId)
                .platformSkuId(skuId).displayThresholdPercent(threshold).status(BuyerProductStatus.ACTIVE.getCode())
                .version(0L).createdBy(1L).updatedBy(1L).createdAt(now).updatedAt(now).build();
        context.getBean(BuyerProductMapper.class).insert(product);
        return product;
    }

    protected ProjectOverrides overrides() {
        return new ProjectOverrides(false,
                new FormulaRule(PriceMode.CUSTOM, PriceBase.SUGGESTED_SALE_PRICE, new BigDecimal("85.25"),
                        FixedOperator.ADD, new BigDecimal("1.50")),
                false, new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null),
                false, new ServiceFeeRule(new BigDecimal("3.25"), FixedOperator.MULTIPLY,
                        new BigDecimal("1.1250"), false, "原始服务费"),
                false, new HideRule(new BigDecimal("2"), new BigDecimal("5")),
                false, new TaxMetadata(false, "NON_TAXABLE", "不征税"));
    }

    protected BuyerProductQueryService.BuyerProductListItem read(Fixture fixture) {
        return query.page(fixture.buyer().getBuyerCode(), List.of(), 1, 100).getList().stream()
                .filter(row -> row.buyerProductCode().equals(fixture.product().getBuyerProductCode()))
                .findFirst().orElseThrow();
    }

    protected BuyerSkuPricingDO identity(Fixture fixture) {
        return context.getBean(BuyerSkuPricingMapper.class).selectByBuyerProductId(fixture.product().getId());
    }

    /** 查询不得创建版本、重算任务或目录投影；逐行快照同时捕获指针、JSON、版本号和更新时间。 */
    protected Map<String, List<Map<String, Object>>> snapshot() {
        var snapshot = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("sc_buyer_product", "sc_buyer_sku_pricing", "sc_buyer_sku_pricing_version",
                "sc_platform_sku_pricing", "sc_platform_sku_pricing_version", "sc_buyer_listing",
                "sc_buyer_listing_version", "sc_buyer_listing_availability", "sc_project_listing",
                "sc_project_sku_pricing", "sc_project_sku_pricing_version", "sc_outbox_event")) {
            snapshot.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }
        return snapshot;
    }

    protected record Fixture(BuyerDO buyer, BuyerProductDO product, PlatformSkuDO sku) { }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import({BuyerSelectionCatalogAdminIntegrationTest.TestConfiguration.class,
            BuyerProductQueryService.class, BuyerAcceptanceCatalogReadService.class})
    static class Configuration {
        @Bean("ss")
        SecurityFrameworkService permissions() { return mock(SecurityFrameworkService.class); }
        @Bean
        BuyerAdminController buyerController(BuyerProductQueryService query, GenericBatchPricingService pricing,
                                             BuyerSelectionCatalogAdminCoordinator coordinator) {
            return new BuyerAdminController(mock(BuyerAdminCommandService.class), mock(BuyerAdminQueryService.class),
                    mock(BuyerProductCommandService.class), query, pricing, mock(BuyerInvoiceService.class), coordinator);
        }
    }
}
