package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.catalog.application.*;
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
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_OFFER_OUT_OF_SCOPE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_SCOPE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 三入口供货范围一致性：独占MySQL8.4/完整Liquibase，真实Mapper分页与Spring事务。
 * 仅发布编排与外部字典挡板，不使用业务库、不调用供应商。
 * 设计见 docs/superpowers/specs/2026-10-20-supplier-supply-scope-matching-design.md#5-自动化门禁与人工验收。
 */
class SupplierSupplyScopeMatchingIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static SupplierCooperativeProductService offers;
    private static ProductCatalogQueryService products;
    private static PlatformSkuQueryService skus;
    private static Long brandId;
    private static Category root;
    private static Category leaf;
    private static Category other;

    private record Category(String code, Long id) { }
    private record Fixture(SupplierDO supplier, String keyword) { }

    @BeforeAll
    static void start() {
        MYSQL.start();
        context = new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE).properties(Map.of(
                "aifuli.supply-chain.enabled", true,
                "aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl(),
                "aifuli.supply-chain.datasource.username", MYSQL.getUsername(),
                "aifuli.supply-chain.datasource.password", MYSQL.getPassword(),
                "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")).run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        offers = context.getBean(SupplierCooperativeProductService.class);
        products = context.getBean(ProductCatalogQueryService.class);
        skus = context.getBean(PlatformSkuQueryService.class);
        var brands = context.getBean(BrandAdminService.class);
        String brand = brands.create(new BrandAdminService.CreateCommand(null, "范围匹配品牌", "范围匹配",
                null, null, null, 0, CatalogEntityStatus.ACTIVE.getCode(), 1L));
        brandId = context.getBean(BrandMapper.class).selectByCode(brand).getId();
        root = category("范围父分类", null);
        leaf = category("范围子分类", root.code());
        other = category("范围其他分类", null);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (MYSQL.isRunning()) MYSQL.stop();
    }

    @Test
    void differentSellableCategoryAllowsBothSelectionsCreateAndEditWithoutPublishingVersions() {
        Fixture f = fixture();
        var scope = scope(f, "PHYSICAL", "EQUITY_PRODUCT", leaf, 100L);
        var sku = sku(f, "EQUITY_PRODUCT", leaf);
        assertThat(aggregate(f, 1, 20).getTotal()).isEqualTo(1L);
        assertThat(pool(f, 1, 20).getTotal()).isEqualTo(1L);
        long pricingBefore = pricingVersions(sku);
        String offerNo = offers.createPublished(f.supplier().getSupplierCode(), List.of(line(sku)), 1L).getFirst();
        offers.updateCommercial(f.supplier().getSupplierCode(), offerNo, line(sku), 1L);
        var saved = context.getBean(SupplierOfferMapper.class).selectByNo(offerNo);
        assertThat(saved.getSupplyScopeId()).isEqualTo(scope.getId());
        assertThat(saved.getDraftMaxQuantity()).isEqualTo(100L);
        assertThat(saved.getStatus()).isEqualTo(OfferStatus.SUSPENDED.getCode());
        assertThat(saved.getCurrentVersionId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_offer_version WHERE offer_id=?", Long.class, saved.getId())).isZero();
        assertThat(pricingVersions(sku)).isEqualTo(pricingBefore);
        assertThat(aggregate(f, 1, 20).getTotal()).isZero();
        assertThat(pool(f, 1, 20).getTotal()).isZero();
    }

    /** 对应原始三SKU请求：同一SPU跨可售品类、只配父分类，整批新增默认下架。 */
    @Test
    void batchCreatesAllThreeSkusInOneSpuUsingCrossCategoryParentScope() {
        Fixture f = fixture();
        var scope = scope(f, "PHYSICAL", "EQUITY_PRODUCT", root, 500L);
        var selected = productSkus(f, "EQUITY_PRODUCT", leaf, 3);
        assertThat(aggregate(f, 1, 20).getList().getFirst().skus()).hasSize(3);
        assertThat(pool(f, 1, 20).getTotal()).isEqualTo(3L);
        var offerNos = offers.createPublished(f.supplier().getSupplierCode(), selected.stream()
                .map(SupplierSupplyScopeMatchingIntegrationTest::line).toList(), 1L);
        assertThat(offerNos).hasSize(3);
        assertThat(offerCount(f)).isEqualTo(3L);
        for (String no : offerNos) {
            var offer = context.getBean(SupplierOfferMapper.class).selectByNo(no);
            assertThat(offer.getSupplyScopeId()).isEqualTo(scope.getId());
            assertThat(offer.getDraftMaxQuantity()).isEqualTo(500L);
            assertThat(offer.getStatus()).isEqualTo(OfferStatus.SUSPENDED.getCode());
            assertThat(offer.getCurrentVersionId()).isNull();
        }
        // 退役后只恢复该SKU的选品资格，不改变其余两个已有合作商品的排除规则。
        offers.retire(f.supplier().getSupplierCode(), offerNos.getFirst(), 1L);
        assertThat(aggregate(f, 1, 20).getList().getFirst().skus()).hasSize(1);
        assertThat(pool(f, 1, 20).getTotal()).isEqualTo(1L);
    }

    @Test
    void parentScopeCoversChildAndMostSpecificScopeDeterminesInheritedLimit() {
        Fixture f = fixture();
        scope(f, "PHYSICAL", "EQUITY_PRODUCT", root, 500L);
        var sku = sku(f, "EQUITY_PRODUCT", leaf);
        assertThat(aggregate(f, 1, 20).getTotal()).isEqualTo(1L);
        assertThat(pool(f, 1, 20).getTotal()).isEqualTo(1L);
        var specific = scope(f, "MOVIE", "EQUITY_PRODUCT", leaf, 50L);
        String no = offers.createPublished(f.supplier().getSupplierCode(), List.of(line(sku)), 1L).getFirst();
        var offer = context.getBean(SupplierOfferMapper.class).selectByNo(no);
        assertThat(offer.getSupplyScopeId()).isEqualTo(specific.getId());
        assertThat(offer.getDraftMaxQuantity()).isEqualTo(50L);
    }

    @Test
    void scopePairsAndPaginationAreAppliedBeforeCountingAndPaging() {
        Fixture f = fixture();
        scope(f, "PHYSICAL", "EQUITY_PRODUCT", root, 100L);
        scope(f, "PHYSICAL", "MOVIE", other, 100L);
        var acceptedA = sku(f, "EQUITY_PRODUCT", leaf);
        var acceptedB = sku(f, "MOVIE", other);
        sku(f, "MOVIE", leaf);
        sku(f, "EQUITY_PRODUCT", other);
        var aggregateCodes = new HashSet<String>();
        var poolCodes = new HashSet<String>();
        for (int page = 1; page <= 2; page++) {
            var aggregate = aggregate(f, page, 1);
            var pool = pool(f, page, 1);
            assertThat(aggregate.getTotal()).isEqualTo(2L);
            assertThat(pool.getTotal()).isEqualTo(2L);
            assertThat(aggregate.getList()).hasSize(1);
            assertThat(pool.getList()).hasSize(1);
            aggregate.getList().forEach(spu -> spu.skus().forEach(sku -> aggregateCodes.add(sku.skuCode())));
            pool.getList().forEach(sku -> poolCodes.add(sku.skuCode()));
        }
        assertThat(aggregateCodes).containsExactlyInAnyOrder(acceptedA.getSkuCode(), acceptedB.getSkuCode());
        assertThat(poolCodes).isEqualTo(aggregateCodes);
        assertThat(aggregate(f, 3, 1).getList()).isEmpty();
        assertThat(pool(f, 3, 1).getList()).isEmpty();
    }

    @Test
    void deepestConflictIsHiddenByBothSelectionsAndRejectsCreateAndEdit() {
        Fixture f = fixture();
        scope(f, "PHYSICAL", "EQUITY_PRODUCT", root, 500L);
        scope(f, "PHYSICAL", "EQUITY_PRODUCT", leaf, 100L);
        var sku = sku(f, "EQUITY_PRODUCT", leaf);
        String no = offers.createPublished(f.supplier().getSupplierCode(), List.of(line(sku)), 1L).getFirst();
        var original = context.getBean(SupplierOfferMapper.class).selectByNo(no);
        scope(f, "EQUITY", "EQUITY_PRODUCT", leaf, 200L);
        var unadded = sku(f, "EQUITY_PRODUCT", leaf);
        assertThat(aggregate(f, 1, 20).getTotal()).isZero();
        assertThat(pool(f, 1, 20).getTotal()).isZero();
        assertThatThrownBy(() -> offers.createPublished(f.supplier().getSupplierCode(), List.of(line(unadded)), 1L))
                .hasFieldOrPropertyWithValue("code", SUPPLIER_SCOPE_CONFLICT.getCode());
        assertThatThrownBy(() -> offers.updateCommercial(f.supplier().getSupplierCode(), no, line(sku), 1L))
                .hasFieldOrPropertyWithValue("code", SUPPLIER_SCOPE_CONFLICT.getCode());
        assertThat(context.getBean(SupplierOfferMapper.class).selectByNo(no)).isEqualTo(original);
        assertThat(offerCount(f)).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidSecondTypeOrClassificationRollsBackFirstCrossCategoryOffer(boolean wrongType) {
        Fixture f = fixture();
        scope(f, "PHYSICAL", "EQUITY_PRODUCT", leaf, 100L);
        var valid = sku(f, "EQUITY_PRODUCT", leaf);
        var invalid = sku(f, wrongType ? "MOVIE" : "EQUITY_PRODUCT", wrongType ? leaf : other);
        long before = pricingVersions(valid);
        assertThatThrownBy(() -> offers.createPublished(f.supplier().getSupplierCode(), List.of(line(valid), line(invalid)), 1L))
                .hasFieldOrPropertyWithValue("code", SUPPLIER_OFFER_OUT_OF_SCOPE.getCode());
        assertThat(offerCount(f)).isZero();
        assertThat(pricingVersions(valid)).isEqualTo(before);
        assertThat(aggregate(f, 1, 20).getTotal()).isEqualTo(1L);
        assertThat(pool(f, 1, 20).getTotal()).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void parentAndChildStatusGuardsRemainEnforced(boolean parentInactive) {
        Fixture f = fixture();
        scope(f, "PHYSICAL", "EQUITY_PRODUCT", leaf, 100L);
        var sku = sku(f, "EQUITY_PRODUCT", leaf);
        if (parentInactive) {
            jdbc.update("UPDATE sc_platform_spu SET status=? WHERE id=?", CatalogEntityStatus.INACTIVE.getCode(), sku.getSpuId());
        } else {
            jdbc.update("UPDATE sc_platform_sku SET status=? WHERE id=?", CatalogEntityStatus.INACTIVE.getCode(), sku.getId());
        }
        assertThat(aggregate(f, 1, 20).getTotal()).isZero();
        assertThat(pool(f, 1, 20).getTotal()).isZero();
        assertThatThrownBy(() -> offers.createPublished(f.supplier().getSupplierCode(), List.of(line(sku)), 1L))
                .hasFieldOrPropertyWithValue("code", SUPPLIER_OFFER_OUT_OF_SCOPE.getCode());
        assertThat(offerCount(f)).isZero();
    }

    @Test
    void noScopesReturnsEmptyButOrdinaryManagementStillListsProduct() {
        Fixture f = fixture();
        sku(f, "EQUITY_PRODUCT", leaf);
        assertThat(aggregate(f, 1, 20).getTotal()).isZero();
        assertThat(pool(f, 1, 20).getTotal()).isZero();
        var normal = products.managementPage(new ProductCatalogQueryService.ProductManagementPageQuery(
                null, f.keyword(), null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, null, false));
        assertThat(normal.getTotal()).isEqualTo(1L);
    }

    private static Fixture fixture() {
        int id = SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        var supplier = SupplierDO.builder().supplierCode("SPL_SCOPE_" + id).supplierName("范围供应商" + id)
                .shortName("范围").supplierType("STANDARD_SUPPLIER").status(SupplierStatus.ACTIVE.getCode())
                .version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierMapper.class).insert(supplier);
        return new Fixture(supplier, "范围组" + id + "#");
    }

    private static SupplierSupplyScopeDO scope(Fixture f, String sellableCategory, String type, Category category, long max) {
        LocalDateTime now = LocalDateTime.now();
        var scope = SupplierSupplyScopeDO.builder().supplierId(f.supplier().getId()).sellableCategory(sellableCategory)
                .productType(type).productClassification(category.code()).maxQuantity(max).minQuantity(1L)
                .version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplierSupplyScopeMapper.class).insert(scope);
        return scope;
    }

    private static Category category(String name, String parent) {
        var categories = context.getBean(CategoryCommandService.class);
        String code = categories.create(new CategoryCommandService.CreateCommand(null, name, null,
                List.of("EQUITY_PRODUCT", "MOVIE"), null, parent, 0, (short) 1, CatalogEntityStatus.ACTIVE.getCode(), 1L));
        return new Category(code, categories.detail(code).getId());
    }

    private static PlatformSkuDO sku(Fixture f, String type, Category category) {
        return productSkus(f, type, category, 1).getFirst();
    }

    private static List<PlatformSkuDO> productSkus(Fixture f, String type, Category category, int count) {
        var lines = java.util.stream.IntStream.range(0, count).mapToObj(index ->
                new SaveSkuLine(null, "范围规格" + index, "DIRECT_RECHARGE", 10000L, 10000L,
                        10000L, 1L, 100L, CatalogEntityStatus.ACTIVE.getCode())).toList();
        String spuCode = context.getBean(ProductCatalogCommandService.class).createProduct(new SaveSpuCommand(
                null, f.keyword() + SEQUENCE.incrementAndGet(), null, brandId, category.id(), List.of(), "EQUITY", type,
                category.code(), null, List.of(), null, null, "VIRTUAL", 1L, 100L, CatalogEntityStatus.ACTIVE.getCode(),
                lines, 1L));
        Long spuId = context.getBean(PlatformSpuMapper.class).selectByCode(spuCode).getId();
        return context.getBean(PlatformSkuMapper.class).selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .eq(PlatformSkuDO::getSpuId, spuId).orderByAsc(PlatformSkuDO::getId));
    }

    private static DraftLine line(PlatformSkuDO sku) {
        return new DraftLine(sku.getSkuCode(), "FIXED", 9500L, null, null, "API_DIRECT", null, null, "ext-" + sku.getSkuCode());
    }

    private static long offerCount(Fixture f) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_offer WHERE supplier_id=?", Long.class, f.supplier().getId());
    }

    private static long pricingVersions(PlatformSkuDO sku) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sc_platform_sku_pricing_version WHERE platform_sku_id=?", Long.class, sku.getId());
    }

    private static com.aifuli.framework.common.pojo.PageResult<ProductCatalogQueryService.ProductManagementSpuItem> aggregate(Fixture f, int page, int size) {
        return products.managementPage(new ProductCatalogQueryService.ProductManagementPageQuery(null, f.keyword(), null,
                null, null, CatalogEntityStatus.ACTIVE.getCode(), null, null, null, null, null, null,
                f.supplier().getSupplierCode(), null, null, page, size, null, true));
    }

    private static com.aifuli.framework.common.pojo.PageResult<PlatformSkuQueryService.SkuListItem> pool(Fixture f, int page, int size) {
        return skus.page(new PlatformSkuQueryService.SkuPageQuery(f.keyword(), null, null, null,
                f.supplier().getSupplierCode(), null, null, page, size));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            ProductCatalogCommandService.class, ProductCatalogQueryService.class, PlatformSkuQueryService.class,
            GenericBatchPricingService.class, SupplierCooperativeProductService.class, ProductCenterTestConfiguration.class})
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean SupplierOfferPublicationService publicationService() { return mock(SupplierOfferPublicationService.class); }
        @Bean TaxRateDictionaryAdapter taxRateDictionaryAdapter() {
            var adapter = mock(TaxRateDictionaryAdapter.class);
            when(adapter.requireEnabled(false, PricingTaxRateCodes.NON_TAXABLE))
                    .thenReturn(new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税"));
            return adapter;
        }
    }
}
