package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.catalog.application.BrandAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSkuLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSpuCommand;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxRateCodes;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.DraftLine;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SKU销售建议价、旧L1兼容及折扣准入真实MySQL/Liquibase门禁。
 * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-sale-price-alignment-design.md#7-验证矩阵与门禁。
 * 固定独占MySQL8.4；真实Spring事务、Mapper和同步通用定价监听器，不连接业务测试库。
 */
class SupplierOfferSalePriceIntegrationTest {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static Long brandId;
    private static Long categoryId;
    private static SupplierCooperativeProductService offers;

    @BeforeAll
    static void start() {
        MYSQL.start();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", MYSQL.getUsername());
        properties.put("aifuli.supply-chain.datasource.password", MYSQL.getPassword());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        context = new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE)
                .properties(properties).run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        offers = context.getBean(SupplierCooperativeProductService.class);
        var brands = context.getBean(BrandAdminService.class);
        String brand = brands.create(new BrandAdminService.CreateCommand(null, "建议价回归品牌", "建议价",
                null, null, null, 0, CatalogEntityStatus.ACTIVE.getCode(), 1L));
        brandId = context.getBean(BrandMapper.class).selectByCode(brand).getId();
        var categories = context.getBean(CategoryCommandService.class);
        String category = categories.create(new CategoryCommandService.CreateCommand(null, "建议价回归分类", null,
                List.of("EQUITY_PRODUCT"), null, null, 0, (short) 1, CatalogEntityStatus.ACTIVE.getCode(), 1L));
        categoryId = categories.detail(category).getId();
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (MYSQL.isRunning()) MYSQL.stop();
    }

    private record Fixture(String supplier, PlatformSkuDO sku) { }

    private static Fixture fixture(long salePrice) {
        String supplierCode = "SPL_PRICE_" + SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        var supplier = SupplierDO.builder().supplierCode(supplierCode).supplierName("建议价回归供应商")
                .shortName("建议价回归").supplierType("STANDARD_SUPPLIER").status(SupplierStatus.ACTIVE.getCode())
                .version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierMapper.class).insert(supplier);
        context.getBean(SupplierSupplyScopeMapper.class).insert(SupplierSupplyScopeDO.builder()
                .supplierId(supplier.getId()).sellableCategory("EQUITY").productType("EQUITY_PRODUCT")
                .productClassification("VIRTUAL_CARD").maxQuantity(100L).minQuantity(1L)
                .version(0L).createdAt(now).updatedAt(now).build());
        return new Fixture(supplierCode, createSku(salePrice));
    }

    private static PlatformSkuDO createSku(long salePrice) {
        var catalog = context.getBean(ProductCatalogCommandService.class);
        String spu = catalog.createProduct(new SaveSpuCommand(null, "建议价回归商品" + SEQUENCE.incrementAndGet(),
                null, brandId, categoryId, List.of(), "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", null, List.of(),
                null, null, "VIRTUAL", 1L, 100L, CatalogEntityStatus.ACTIVE.getCode(),
                List.of(new SaveSkuLine(null, "建议价回归规格", "DIRECT_RECHARGE", 10000L, 10000L,
                        salePrice, 1L, 100L, CatalogEntityStatus.ACTIVE.getCode())), 1L));
        Long spuId = context.getBean(PlatformSpuMapper.class).selectByCode(spu).getId();
        return context.getBean(PlatformSkuMapper.class).selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .eq(PlatformSkuDO::getSpuId, spuId)).getFirst();
    }

    private static DraftLine discount(PlatformSkuDO sku, Long hint) {
        return new DraftLine(sku.getSkuCode(), "DISCOUNT", null, 9000, hint, "API_DIRECT", 100L, 1L, "external-" + sku.getSkuCode());
    }

    private static DraftLine fixed(PlatformSkuDO sku) {
        return new DraftLine(sku.getSkuCode(), "FIXED", 9500L, null, null, "API_DIRECT", 100L, 1L, "external-" + sku.getSkuCode());
    }

    private static long pricingVersions(PlatformSkuDO sku) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version WHERE platform_sku_id=?",
                Long.class, sku.getId());
    }

    private static long currentPricing(PlatformSkuDO sku) {
        return jdbc.queryForObject("SELECT current_version_id FROM sc_platform_sku_pricing WHERE platform_sku_id=?",
                Long.class, sku.getId());
    }

    private static com.fasterxml.jackson.databind.JsonNode page(Fixture fixture) {
        return new ObjectMapper().valueToTree(offers.page(fixture.supplier(), null, null, null, 1, 20).getList().getFirst());
    }

    @Test
    void skuPriceIsReturnedWithoutL1AndRemainsDistinctWhenL1Exists() {
        Fixture f = fixture(9600);
        String no = offers.createPublished(f.supplier(), List.of(fixed(f.sku())), 1L).getFirst();
        assertThat(page(f).path("suggestedSalePriceCents").asLong(-1)).isEqualTo(9600);
        assertThat(page(f).path("suggestPriceCents").isNull()).isTrue();
        jdbc.update("INSERT INTO sc_platform_settlement_price_version "
                + "(platform_sku_id,version_no,unit_price_cents,currency,published_at,created_at) VALUES (?,1,8000,'CNY',NOW(3),NOW(3))",
                f.sku().getId());
        Long legacyVersion = jdbc.queryForObject("SELECT id FROM sc_platform_settlement_price_version WHERE platform_sku_id=?",
                Long.class, f.sku().getId());
        jdbc.update("INSERT INTO sc_platform_settlement_price(platform_sku_id,current_version_id,version,updated_at) "
                + "VALUES (?,?,0,NOW(3))", f.sku().getId(), legacyVersion);
        assertThat(page(f).path("suggestedSalePriceCents").asLong(-1)).isEqualTo(9600);
        assertThat(page(f).path("suggestPriceCents").asLong()).isEqualTo(8000);
        assertThat(page(f).path("unitPriceCents").asLong()).isEqualTo(9500);
        assertThat(context.getBean(SupplierOfferMapper.class).selectByNo(no).getCurrentVersionId()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, 1, 10000})
    void discountCreateAndUpdateIgnoreMissingOrForgedClientHint(Long hint) {
        Fixture f = fixture(9600);
        long before = pricingVersions(f.sku());
        String no = offers.createPublished(f.supplier(), List.of(discount(f.sku(), hint)), 1L).getFirst();
        assertThat(pricingVersions(f.sku())).isEqualTo(before + 1);
        long createdPricing = currentPricing(f.sku());
        assertThat(jdbc.queryForObject("SELECT supply_price_cents FROM sc_platform_sku_pricing_version WHERE id=?",
                Long.class, createdPricing)).isEqualTo(8640L);
        offers.updateCommercial(f.supplier(), no, discount(f.sku(), hint), 1L);
        assertThat(pricingVersions(f.sku())).isEqualTo(before + 2);
        var offer = context.getBean(SupplierOfferMapper.class).selectByNo(no);
        assertThat(offer.getDiscountBp()).isEqualTo(9000);
        assertThat(offer.getDraftUnitPrice()).isNull();
        assertThat(offer.getCurrentVersionId()).isNull();
        assertThat(offer.getStabilityScore()).isEqualTo(80);
        assertThat(offer.getTimelinessScore()).isEqualTo(80);
        assertThat(page(f).path("suggestedSalePriceCents").asLong(-1)).isEqualTo(9600L);
    }

    @Test
    void zeroSkuPriceRejectsCreateAndUpdateWithoutOfferOrPricingWrites() {
        Fixture f = fixture(0);
        long before = pricingVersions(f.sku());
        long pointer = currentPricing(f.sku());
        assertInvalid(() -> offers.createPublished(f.supplier(), List.of(discount(f.sku(), 10000L)), 1L));
        assertThat(offers.page(f.supplier(), null, null, null, 1, 20).getTotal()).isZero();
        assertThat(pricingVersions(f.sku())).isEqualTo(before);
        assertThat(currentPricing(f.sku())).isEqualTo(pointer);
        String no = offers.createPublished(f.supplier(), List.of(fixed(f.sku())), 1L).getFirst();
        var original = context.getBean(SupplierOfferMapper.class).selectByNo(no);
        before = pricingVersions(f.sku());
        pointer = currentPricing(f.sku());
        assertInvalid(() -> offers.updateCommercial(f.supplier(), no, discount(f.sku(), 10000L), 1L));
        assertThat(context.getBean(SupplierOfferMapper.class).selectByNo(no)).isEqualTo(original);
        assertThat(pricingVersions(f.sku())).isEqualTo(before);
        assertThat(currentPricing(f.sku())).isEqualTo(pointer);
        assertThat(page(f).path("suggestedSalePriceCents").asLong(-1)).isZero();
    }

    /** 首行已插入且真实同步定价版本已写入，第二行业务拒绝须将整批及首行价格版本/指针一并回滚。 */
    @Test
    void invalidSecondDiscountRollsBackFirstOfferAndSynchronousPricingVersion() {
        Fixture f = fixture(9600);
        PlatformSkuDO invalid = createSku(0);
        long before = pricingVersions(f.sku());
        long pointer = currentPricing(f.sku());
        Map<String, Object> identity = jdbc.queryForMap("SELECT * FROM sc_platform_sku_pricing WHERE platform_sku_id=?", f.sku().getId());
        List<Map<String, Object>> versions = jdbc.queryForList("SELECT * FROM sc_platform_sku_pricing_version WHERE platform_sku_id=? ORDER BY id", f.sku().getId());
        assertInvalid(() -> offers.createPublished(f.supplier(),
                List.of(fixed(f.sku()), discount(invalid, 10000L)), 1L));
        assertThat(offers.page(f.supplier(), null, null, null, 1, 20).getTotal()).isZero();
        assertThat(pricingVersions(f.sku())).isEqualTo(before);
        assertThat(currentPricing(f.sku())).isEqualTo(pointer);
        assertThat(jdbc.queryForMap("SELECT * FROM sc_platform_sku_pricing WHERE platform_sku_id=?", f.sku().getId())).isEqualTo(identity);
        assertThat(jdbc.queryForList("SELECT * FROM sc_platform_sku_pricing_version WHERE platform_sku_id=? ORDER BY id", f.sku().getId())).isEqualTo(versions);
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ServiceException.class)
                .hasMessage("折扣模式要求SKU销售建议价大于0，请先在商品中心配置")
                .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(1_050_001_178);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            ProductCatalogCommandService.class, GenericBatchPricingService.class,
            SupplierCooperativeProductService.class, ProductCenterTestConfiguration.class})
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean TaxRateDictionaryAdapter taxRateDictionaryAdapter() {
            TaxRateDictionaryAdapter adapter = mock(TaxRateDictionaryAdapter.class);
            when(adapter.requireEnabled(false, PricingTaxRateCodes.NON_TAXABLE))
                    .thenReturn(new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税"));
            return adapter;
        }
    }
}
