package com.aifuli.module.supplychain.tradecore.catalog;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.catalog.application.BrandAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService.SkuPageQuery;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSkuLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSpuCommand;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingTaxRateCodes;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void persistsCompositeProductAndReadsStableManagementProjections() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BrandAdminService brandService = context.getBean(BrandAdminService.class);
            CategoryCommandService categoryService = context.getBean(CategoryCommandService.class);
            CategoryQueryService categoryQueryService = context.getBean(CategoryQueryService.class);
            ProductCatalogCommandService commandService = context.getBean(ProductCatalogCommandService.class);
            ProductCatalogQueryService queryService = context.getBean(ProductCatalogQueryService.class);
            PlatformSkuQueryService platformSkuQueryService = context.getBean(PlatformSkuQueryService.class);
            BrandMapper brandMapper = context.getBean(BrandMapper.class);
            PlatformSkuMapper skuMapper = context.getBean(PlatformSkuMapper.class);
            PlatformSkuPricingMapper platformPricingMapper = context.getBean(PlatformSkuPricingMapper.class);
            PlatformSkuPricingVersionMapper platformPricingVersionMapper =
                    context.getBean(PlatformSkuPricingVersionMapper.class);
            PlatformSettlementPriceMapper priceMapper = context.getBean(PlatformSettlementPriceMapper.class);
            PlatformSettlementPriceVersionMapper priceVersionMapper =
                    context.getBean(PlatformSettlementPriceVersionMapper.class);
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            String brandCode = brandService.create(new BrandAdminService.CreateCommand(
                    "BRD_001", "商品中心测试品牌", "PC品牌", null, "https://example.com/logo.png",
                    "商品中心测试品牌说明", 0, CatalogEntityStatus.ACTIVE.getCode(), 1L));
            String categoryCode = categoryService.create(new CategoryCommandService.CreateCommand(
                    "CAT_001", "商品中心测试分类", null, List.of("EQUITY_PRODUCT", "MOVIE", "DINING"),
                    "商品中心测试分类描述", null, 0, (short) 1,
                    CatalogEntityStatus.ACTIVE.getCode(), 1L));
            Long brandId = brandMapper.selectByCode(brandCode).getId();
            Long categoryId = categoryService.detail(categoryCode).getId();

            CategoryQueryService.CategoryDetail categoryDetail = categoryQueryService.detail(categoryCode);
            assertThat(categoryDetail.categoryCode()).isEqualTo(categoryCode);
            assertThat(categoryDetail.categoryNodeNo()).isEqualTo((short) 1);
            assertThat(categoryDetail.categoryName()).isEqualTo("商品中心测试分类");
            assertThat(categoryDetail.productType()).isEqualTo("EQUITY_PRODUCT");
            assertThat(categoryDetail.typeCodes()).containsExactly("EQUITY_PRODUCT", "MOVIE", "DINING");
            assertThat(categoryDetail.description()).isEqualTo("商品中心测试分类描述");
            assertThat(categoryDetail.remark()).isEqualTo("商品中心测试分类描述");
            JsonNode categoryJson = objectMapper.valueToTree(categoryDetail);
            Set<String> categoryFields = new HashSet<>();
            categoryJson.fieldNames().forEachRemaining(categoryFields::add);
            assertThat(categoryFields).containsExactlyInAnyOrder(
                    "categoryCode", "categoryNodeNo", "categoryName", "productType", "typeCodes", "description", "remark", "parentId",
                    "categoryLevel", "sort", "visible", "status", "createdAt", "updatedAt");
            assertThat(categoryFields).doesNotContain("id", "version", "createdBy", "updatedBy");

            List<CategoryQueryService.ManagementCategoryNode> treeNodes = categoryQueryService.managementTree();
            assertThat(treeNodes).extracting(CategoryQueryService.ManagementCategoryNode::categoryCode)
                    .contains(categoryCode);
            CategoryQueryService.ManagementCategoryNode treeNode = treeNodes.stream()
                    .filter(n -> categoryCode.equals(n.categoryCode())).findFirst().orElseThrow();
            assertThat(treeNode.productType()).isEqualTo("EQUITY_PRODUCT");
            assertThat(treeNode.categoryNodeNo()).isEqualTo((short) 1);
            assertThat(treeNode.typeCodes()).containsExactly("EQUITY_PRODUCT", "MOVIE", "DINING");
            assertThat(treeNode.remark()).isEqualTo("商品中心测试分类描述");
            assertThat(treeNode.createdAt()).isNotNull();
            assertThat(treeNode.updatedAt()).isNotNull();

            // 测试编辑多商品类型与清空描述
            categoryService.update(categoryCode, new CategoryCommandService.UpdateCommand(
                    "商品中心测试分类已更新", null, List.of("MOVIE", "DINING"), "更新后备注", null, 1, (short) 1, 1L));
            CategoryQueryService.CategoryDetail updatedDetail = categoryQueryService.detail(categoryCode);
            assertThat(updatedDetail.categoryName()).isEqualTo("商品中心测试分类已更新");
            assertThat(updatedDetail.productType()).isEqualTo("MOVIE");
            assertThat(updatedDetail.typeCodes()).containsExactly("MOVIE", "DINING");
            assertThat(updatedDetail.remark()).isEqualTo("更新后备注");

            categoryService.update(categoryCode, new CategoryCommandService.UpdateCommand(
                    "商品中心测试分类已更新", null, null, null, null, null, null, 1L));
            CategoryQueryService.CategoryDetail clearedDescDetail = categoryQueryService.detail(categoryCode);
            assertThat(clearedDescDetail.description()).isNull();
            assertThat(clearedDescDetail.remark()).isNull();
            assertThat(clearedDescDetail.typeCodes()).containsExactly("MOVIE", "DINING");

            String spuCode = commandService.createProduct(new SaveSpuCommand(
                    "SPU_001", "商品中心虚拟直充", "复合保存回归", brandId, categoryId, List.of(),
                    "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", null, List.of(), "测试说明",
                    "测试详情", "VIRTUAL", 1L, 100L, CatalogEntityStatus.ACTIVE.getCode(),
                    List.of(new SaveSkuLine("SKU_001_100", "商品中心直充100元",
                            "https://example.com/sku-main.png",
                            List.of("https://example.com/sku-detail-1.png", "https://example.com/sku-detail-2.png"),
                            "SKU商品描述", "DIRECT_RECHARGE",
                            10_000L, 12_000L, 9_900L, SuggestedSalePriceRule.fixed(9_900L),
                            true, new BigDecimal("0.0600"), 1L, 10L,
                            true, "MONTH", 2L, true, true, "DAY", 2L, 1000L,
                            CatalogEntityStatus.ACTIVE.getCode())), 1L));

            PlatformSkuDO platformSku = skuMapper.selectByCode("SKU_001_100");
            assertThat(platformSku.getFaceValue()).isEqualTo(10_000L);
            assertThat(platformSku.getMarketPrice()).isEqualTo(12_000L);
            assertThat(platformSku.getSuggestedSalePrice()).isEqualTo(9_900L);
            assertThat(platformSku.getSuggestedSalePriceRuleJson().mode())
                    .isEqualTo(com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.Mode.FIXED);
            assertThat(platformSku.getSuggestedSalePriceRuleJson().fixedPriceCents()).isEqualTo(9_900L);
            assertThat(platformSku.getMainImageUrl()).isEqualTo("https://example.com/sku-main.png");
            assertThat(platformSku.getDetailImageUrls()).containsExactly(
                    "https://example.com/sku-detail-1.png", "https://example.com/sku-detail-2.png");
            assertThat(platformSku.getDescription()).isEqualTo("SKU商品描述");
            assertThat(platformSku.getTaxIncluded()).isTrue();
            assertThat(platformSku.getTaxRate()).isEqualByComparingTo("0.0600");
            assertThat(platformSku.getUserLimitEnabled()).isTrue();
            assertThat(platformSku.getUserLimitPeriod()).isEqualTo("MONTH");
            assertThat(platformSku.getUserLimitQuantity()).isEqualTo(2L);
            assertThat(platformSku.getOrderLimitEnabled()).isTrue();
            assertThat(platformSku.getPhoneBindLimitEnabled()).isTrue();
            assertThat(platformSku.getPhoneBindLimitPeriod()).isEqualTo("DAY");
            assertThat(platformSku.getPhoneBindLimitQuantity()).isEqualTo(2L);
            assertThat(platformSku.getSkuInventory()).isEqualTo(1000L);
            assertThat(platformSku.getTargetSchemaCode()).isEqualTo("DIRECT_RECHARGE_TARGET");
            assertThat(platformSku.getTargetSchemaVersion()).isEqualTo(1);
            assertThat(platformSku.getResultSchemaCode()).isEqualTo("DIRECT_RECHARGE_RESULT");
            assertThat(platformSku.getResultSchemaVersion()).isEqualTo(1);
            PlatformSkuPricingDO defaultPricing = platformPricingMapper.selectById(platformSku.getId());
            assertThat(defaultPricing.getPricingStatus()).isEqualTo(PricingStatus.DEPENDENCY_MISSING.name());
            assertThat(defaultPricing.getCurrentVersionId()).isNotNull();
            PlatformSkuPricingVersionDO defaultVersion =
                    platformPricingVersionMapper.selectById(defaultPricing.getCurrentVersionId());
            assertThat(defaultVersion.getDistributionPriceCents()).isEqualTo(12_000L);
            assertThat(defaultVersion.getSalesPriceCents()).isEqualTo(12_000L);
            PricingConfig defaultConfig = objectMapper.readValue(
                    defaultVersion.getEffectiveConfigJson(), PricingConfig.class);
            assertThat(defaultConfig.hideRule().distributionThresholdPercent()).isEqualByComparingTo("0");
            assertThat(defaultConfig.hideRule().salesThresholdPercent()).isEqualByComparingTo("0");
            assertThat(defaultConfig.taxMetadata()).isEqualTo(
                    new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税"));
            LocalDateTime now = LocalDateTime.now();
            PlatformSettlementPriceVersionDO priceVersion = PlatformSettlementPriceVersionDO.builder()
                    .platformSkuId(platformSku.getId())
                    .versionNo(1)
                    .unitPriceCents(8_800L)
                    .currency(Money.CURRENCY_CNY)
                    .publishedBy(1L)
                    .publishedAt(now)
                    .createdAt(now)
                    .build();
            priceVersionMapper.insert(priceVersion);
            priceMapper.insert(PlatformSettlementPriceDO.builder()
                    .platformSkuId(platformSku.getId())
                    .currentVersionId(priceVersion.getId())
                    .version(0L)
                    .updatedAt(now)
                    .build());

            PlatformSkuQueryService.SkuListItem skuListItem = platformSkuQueryService.page(new SkuPageQuery(
                    "商品中心直充100元", null, null, null, null, null, null, 1, 20)).getList().getFirst();
            assertThat(skuListItem.suggestPriceCents()).isEqualTo(8_800L);
            assertThat(skuListItem.marketPriceCents()).isEqualTo(9_900L);

            commandService.batchUpdateSkuTaxMetadata(new ProductCatalogCommandService.SkuTaxMetadataBatchCommand(
                    List.of("SKU_001_100"), false, new BigDecimal("0.1300"), 1L));
            PlatformSkuDO taxUpdatedSku = skuMapper.selectByCode("SKU_001_100");
            assertThat(taxUpdatedSku.getTaxIncluded()).isFalse();
            assertThat(taxUpdatedSku.getTaxRate()).isEqualByComparingTo("0.1300");
            assertThat(taxUpdatedSku.getMarketPrice()).isEqualTo(12_000L);
            assertThat(taxUpdatedSku.getSuggestedSalePrice()).isEqualTo(9_900L);
            assertThat(priceVersionMapper.selectById(priceVersion.getId()).getUnitPriceCents()).isEqualTo(8_800L);

            ProductCatalogQueryService.SpuDetail created = queryService.detail(spuCode);
            assertThat(created.userLimitEnabled()).isTrue();
            assertThat(created.userLimitPeriod()).isEqualTo("MONTH");
            assertThat(created.userLimitQuantity()).isEqualTo(2L);
            assertThat(created.orderLimitEnabled()).isTrue();
            assertThat(created.phoneBindLimitEnabled()).isTrue();
            assertThat(created.phoneBindLimitPeriod()).isEqualTo("DAY");
            assertThat(created.phoneBindLimitQuantity()).isEqualTo(2L);
            JsonNode createdJson = objectMapper.valueToTree(created);
            assertThat(createdJson.get("status").isTextual()).isTrue();
            assertThat(createdJson.get("status").asText())
                    .isEqualTo(String.valueOf(CatalogEntityStatus.ACTIVE.getCode()));
            assertThat(createdJson.get("skus").get(0).get("status").isTextual()).isTrue();
            assertThat(created.skus()).singleElement().satisfies(sku -> {
                assertThat(sku.skuCode()).isEqualTo("SKU_001_100");
                assertThat(sku.mainImageUrl()).isEqualTo("https://example.com/sku-main.png");
                assertThat(sku.detailImageUrls()).containsExactly(
                        "https://example.com/sku-detail-1.png", "https://example.com/sku-detail-2.png");
                assertThat(sku.description()).isEqualTo("SKU商品描述");
                assertThat(sku.suggestedSalePriceRule().mode()).isEqualTo("FIXED");
                assertThat(sku.suggestedSalePriceRule().fixedPriceCents()).isEqualTo(9_900L);
                assertThat(sku.effectiveSellable()).isTrue();
                assertThat(sku.taxIncluded()).isFalse();
                assertThat(sku.taxRate()).isEqualByComparingTo("0.1300");
                assertThat(sku.userLimitEnabled()).isTrue();
                assertThat(sku.userLimitPeriod()).isEqualTo("MONTH");
                assertThat(sku.userLimitQuantity()).isEqualTo(2L);
                assertThat(sku.orderLimitEnabled()).isTrue();
                assertThat(sku.phoneBindLimitEnabled()).isTrue();
                assertThat(sku.phoneBindLimitPeriod()).isEqualTo("DAY");
                assertThat(sku.phoneBindLimitQuantity()).isEqualTo(2L);
                assertThat(sku.skuInventory()).isEqualTo(1000L);
            });

            SuggestedSalePriceRule customRule = new SuggestedSalePriceRule(
                    SuggestedSalePriceRule.Mode.CUSTOM,
                    SuggestedSalePriceRule.Base.MARKET_PRICE,
                    new BigDecimal("50"),
                    SuggestedSalePriceRule.Operator.ADD,
                    new BigDecimal("1.00"), null, null);
            SaveSkuLine validNewSku = new SaveSkuLine(
                    "SKU_001_200", "商品中心直充200元", "DIRECT_RECHARGE",
                    20_000L, 20_000L, null, SuggestedSalePriceRule.equalMarketPrice(),
                    1L, 10L, CatalogEntityStatus.DRAFT.getCode());
            SaveSkuLine mismatchedExistingSku = new SaveSkuLine(
                    "SKU_001_100", "商品中心直充100元",
                    "https://example.com/sku-main.png",
                    List.of("https://example.com/sku-detail-1.png", "https://example.com/sku-detail-2.png"),
                    "SKU商品描述", "DIRECT_RECHARGE",
                    10_000L, 12_000L, 6_099L, customRule,
                    false, new BigDecimal("0.1300"), 1L, 10L,
                    true, "MONTH", 2L, true, true, "DAY", 2L,
                    CatalogEntityStatus.ACTIVE.getCode());

            assertThatThrownBy(() -> commandService.updateProduct(spuCode, new SaveSpuCommand(
                    spuCode, "商品中心虚拟直充", "本次修改必须回滚", brandId, categoryId, List.of(),
                    "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", null, List.of(), "测试说明",
                    "测试详情", "VIRTUAL", "DIRECT_RECHARGE", 1L, 100L,
                    CatalogEntityStatus.ACTIVE.getCode(),
                    List.of(validNewSku, mismatchedExistingSku), 1L)))
                    .isInstanceOf(ServiceException.class);
            PlatformSkuDO rolledBackSku = skuMapper.selectByCode("SKU_001_100");
            assertThat(rolledBackSku.getSuggestedSalePrice()).isEqualTo(9_900L);
            assertThat(rolledBackSku.getSuggestedSalePriceRuleJson().mode())
                    .isEqualTo(SuggestedSalePriceRule.Mode.FIXED);
            assertThat(skuMapper.selectByCode("SKU_001_200")).isNull();
            assertThat(queryService.detail(spuCode).subtitle()).isEqualTo("复合保存回归");

            SaveSkuLine validExistingSku = new SaveSkuLine(
                    "SKU_001_100", "商品中心直充100元",
                    "https://example.com/sku-main.png",
                    List.of("https://example.com/sku-detail-1.png", "https://example.com/sku-detail-2.png"),
                    "SKU商品描述", "DIRECT_RECHARGE",
                    10_000L, 12_000L, null, customRule,
                    false, new BigDecimal("0.1300"), 1L, 10L,
                    true, "MONTH", 2L, true, true, "DAY", 2L,
                    CatalogEntityStatus.ACTIVE.getCode());
            commandService.updateProduct(spuCode, new SaveSpuCommand(
                    spuCode, "商品中心虚拟直充", "复合保存回归已更新", brandId, categoryId, List.of(),
                    "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD", null, List.of(), "测试说明",
                    "测试详情", "VIRTUAL", "DIRECT_RECHARGE", 1L, 100L,
                    CatalogEntityStatus.ACTIVE.getCode(), List.of(validExistingSku), 1L));
            PlatformSkuDO repricedSku = skuMapper.selectByCode("SKU_001_100");
            assertThat(repricedSku.getSuggestedSalePrice()).isEqualTo(6_100L);
            assertThat(repricedSku.getSuggestedSalePriceRuleJson()).satisfies(persistedRule -> {
                assertThat(persistedRule.mode()).isEqualTo(customRule.mode());
                assertThat(persistedRule.base()).isEqualTo(customRule.base());
                assertThat(persistedRule.percentage()).isEqualByComparingTo(customRule.percentage());
                assertThat(persistedRule.operator()).isEqualTo(customRule.operator());
                assertThat(persistedRule.operand()).isEqualByComparingTo(customRule.operand());
            });
            assertThat(queryService.detail(spuCode).skus()).singleElement().satisfies(sku -> {
                assertThat(sku.suggestedSalePrice()).isEqualTo(6_100L);
                assertThat(sku.suggestedSalePriceRule().mode()).isEqualTo("CUSTOM");
                assertThat(sku.suggestedSalePriceRule().operator()).isEqualTo("ADD");
            });

            commandService.changeSkuStatus("SKU_001_100", CatalogEntityStatus.INACTIVE, 1L);
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
