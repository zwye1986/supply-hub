package com.aifuli.module.supplychain.tradecore.buyer;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.CreateBuyerCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminCommandService.CreateProjectCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminCommandService.UpdateProjectCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminQueryService;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.ProductLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingCommandService.ListingLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingPriceSource;
import com.aifuli.module.supplychain.tradecore.catalog.application.SettlementPriceResolver;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingDependencyChangedEvent;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.*;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.L3AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.ListingPriceSource;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PRODUCT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_BUYER_IMMUTABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_LISTING_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_SKU_NOT_IN_BUYER_CATALOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 项目商品归属、L2/L3 批量定价、冻结版本、事务回滚与并发行锁的真实 MySQL 集成门禁。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#6-测试策略}。
 */
class ProjectAdminIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final long L1_CENTS = 9800L;
    private static final int L2_PERCENT = 85;
    private static final long L2_AMOUNT_CENTS = 170L;
    private static final long L2_CENTS = 8500L;
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final String KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);

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
    void projectAndBatchPricingPersistIsolationRollbackAndConcurrency() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BuyerAdminCommandService buyers = context.getBean(BuyerAdminCommandService.class);
            BuyerAdminQueryService buyerQueries = context.getBean(BuyerAdminQueryService.class);
            ProjectAdminQueryService projectQueries = context.getBean(ProjectAdminQueryService.class);
            BuyerProductCommandService products = context.getBean(BuyerProductCommandService.class);
            ProjectAdminCommandService projects = context.getBean(ProjectAdminCommandService.class);
            ProjectListingCommandService listings = context.getBean(ProjectListingCommandService.class);
            ProjectListingQueryService listingQuery = context.getBean(ProjectListingQueryService.class);
            ProjectListingMapper listingMapper = context.getBean(ProjectListingMapper.class);
            ProjectListingVersionMapper listingVersionMapper =
                    context.getBean(ProjectListingVersionMapper.class);

            String buyerA = buyers.create(createBuyer("一级", "一级科技有限公司", "91110000000000001X"));
            String buyerB = buyers.create(createBuyer("另一级", "另一级科技有限公司", "91110000000000002X"));
            seedCatalog(context.getBean(ProductCatalogCommandService.class));
            insertL1(context, "SKU_001_100", L1_CENTS);

            List<String> buyerProductCodes = products.addProducts(buyerA, List.of(
                    new ProductLine("SKU_001_100", L2AdjustMode.FOLLOW_TEMPLATE, null, null, null, null)), 1L);
            assertThat(buyerProductCodes).hasSize(1);

            String projectCode = projects.create(new CreateProjectCommand(
                    "福利项目", buyerA, "WELFARE",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    null, "说明", "DRAFT", 1L));
            assertThat(projectCode).startsWith("PRJ_");
            projects.update(new UpdateProjectCommand(
                    projectCode, "福利项目", buyerA, "WELFARE",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    null, "说明", "ACTIVE", 1L));
            assertThat(projectQueries.get(projectCode).status()).isEqualTo("ACTIVE");
            assertThat(projectQueries.get(projectCode).productTypes())
                    .containsExactlyElementsOf(buyerQueries.get(buyerA).productTypes());

            var buyerPage = buyerQueries.page(new BuyerAdminQueryService.BuyerPageQuery(
                    null, buyerA, List.of(), List.of(), List.of(), List.of(), null, null, 1, 20));
            assertThat(buyerPage.getTotal()).isEqualTo(1L);
            var buyerRow = buyerPage.getList().getFirst();
            assertThat(buyerRow.sellableCategories()).containsExactly("MOVIE");
            assertThat(buyerRow.productTypes()).containsExactly("MOVIE");
            assertThat(buyerRow.projectCount()).isEqualTo(1L);
            assertThat(buyerRow.skuCount()).isEqualTo(1L);
            assertThat(buyerRow.cooperationTime()).isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 30));
            assertThat(buyerQueries.get(buyerA).sellableCategories()).containsExactly("MOVIE");

            assertThatThrownBy(() -> listings.addProducts(projectCode, List.of(
                    new ListingLine("SKU_001_200", L3AdjustMode.FOLLOW_L2, null, null)), 1L))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(PROJECT_SKU_NOT_IN_BUYER_CATALOG.getCode());

            List<String> listingCodes = listings.addProducts(projectCode, List.of(
                    new ListingLine("SKU_001_100", L3AdjustMode.FOLLOW_L2, null, null)), 1L);
            assertThat(listingCodes).hasSize(1);
            String listingCode = listingCodes.getFirst();
            assertThat(listingCode).startsWith("PLT_");
            ProjectListingDO listing = listingMapper.selectByCode(listingCode);
            assertThat(listing.getStatus()).isEqualTo(ProjectListingStatus.AVAILABLE.getCode());
            assertThat(listing.getCurrentVersionId()).isNotNull();
            ProjectListingVersionDO version1 = listingVersionMapper.selectById(listing.getCurrentVersionId());
            assertThat(version1.getBuyerSettlementUnitPrice()).isEqualTo(L1_CENTS);
            assertThat(version1.getSettlementPriceLevel()).isEqualTo(PriceSource.L1.name());
            assertThat(version1.getPriceSource()).isEqualTo(ListingPriceSource.INHERITED.name());
            assertThat(version1.getVersionNo()).isEqualTo(1);
            BuyerProductQueryService buyerProductQuery = context.getBean(BuyerProductQueryService.class);
            assertThat(buyerProductQuery.page(buyerA, List.of(), 1, 20).getTotal()).isEqualTo(1L);
            assertThat(buyerProductQuery.page(buyerA, List.of(), projectCode, 1, 1).getTotal()).isZero();
            var initialListingPage = listingQuery.page(projectCode, 1, 20).getList();
            assertThat(initialListingPage).hasSize(1);
            var displayRow = initialListingPage.getFirst();
            assertThat(displayRow.spuCode()).isEqualTo("SPU_001");
            assertThat(displayRow.spuName()).isEqualTo("电影票");
            assertThat(displayRow.productType()).isEqualTo("MOVIE");
            assertThat(displayRow.productTypeName()).isNotBlank();
            assertThat(displayRow.productClassification()).isEqualTo("MOVIE_TICKET");
            assertThat(displayRow.categoryName()).isEqualTo("电影快充");
            assertThat(displayRow.marketPriceCents()).isEqualTo(0L);
            var moviePage = listingQuery.page(projectCode, List.of("MOVIE"), 1, 1);
            assertThat(moviePage.getTotal()).isEqualTo(1L);
            assertThat(moviePage.getList()).extracting(ProjectListingQueryService.ProjectListingListItem::listingCode)
                    .containsExactly(listingCode);
            assertThat(listingQuery.page(projectCode, List.of("EQUITY_PRODUCT"), 1, 1).getTotal())
                    .isZero();
            assertThat(listingQuery.page(projectCode, List.of("  ", "MOVIE,EQUITY_PRODUCT"), 1, 1)
                    .getTotal()).isEqualTo(1L);
            assertThat(listingQuery.page(projectCode, List.of("MOVIE"), 2, 1).getList()).isEmpty();
            assertThat(listingQuery.page(projectCode, List.of("MOVIE"), 1, PageParam.PAGE_SIZE_NONE)
                    .getTotal()).isEqualTo(1L);
            assertThat(initialListingPage.getFirst().distPriceCents()).isEqualTo(L1_CENTS);
            assertThat(initialListingPage.getFirst().finalPriceCents()).isEqualTo(L1_CENTS);
            assertThat(initialListingPage.getFirst().priceSource()).isEqualTo(PriceSource.L1.name());
            Long version1Id = version1.getId();

            products.patchL2(buyerA, buyerProductCodes.getFirst(), L2AdjustMode.CUSTOM,
                    L2_PERCENT, L2_AMOUNT_CENTS, null, 1L);
            ProjectListingVersionDO afterL2 = listingVersionMapper.selectById(version1Id);
            assertThat(afterL2.getBuyerSettlementUnitPrice()).isEqualTo(L1_CENTS);
            ProjectListingDO stillSamePointer = listingMapper.selectByCode(listingCode);
            assertThat(stillSamePointer.getCurrentVersionId()).isEqualTo(version1Id);
            var listingPageAfterL2 = listingQuery.page(projectCode, 1, 20).getList();
            assertThat(listingPageAfterL2).hasSize(1);
            assertThat(listingPageAfterL2.getFirst().finalPriceCents()).isEqualTo(L1_CENTS);
            assertThat(listingPageAfterL2.getFirst().distPriceCents()).isEqualTo(L2_CENTS);
            assertThat(listingPageAfterL2.getFirst().projectPriceCovered()).isFalse();

            listings.patchL3(projectCode, listingCode, L3AdjustMode.CUSTOM, 90, 0L, 1L);
            ProjectListingDO afterL3 = listingMapper.selectByCode(listingCode);
            assertThat(afterL3.getCurrentVersionId()).isNotEqualTo(version1Id);
            ProjectListingVersionDO version2 = listingVersionMapper.selectById(afterL3.getCurrentVersionId());
            assertThat(version2.getVersionNo()).isEqualTo(2);
            assertThat(version2.getSettlementPriceLevel()).isEqualTo(PriceSource.L3.name());
            assertThat(version2.getPriceSource()).isEqualTo(ListingPriceSource.PROJECT_OVERRIDE.name());
            assertThat(listingVersionMapper.selectById(version1Id).getBuyerSettlementUnitPrice())
                    .isEqualTo(L1_CENTS);

            verifyBatchPricingDatabaseSemantics(context, buyerA, buyerB, projectCode,
                    buyerProductCodes.getFirst(), listingCode);

            // 历史 L1/L2 的事务与冻结测试完成后，再发布通用版本，验证当前版本优先且旧冻结价不回放。
            Long frozenBeforeGeneric = listingVersionMapper.selectById(
                    listingMapper.selectByCode(listingCode).getCurrentVersionId()).getBuyerSettlementUnitPrice();
            verifyGenericBatchPricing(context, buyerA, projectCode,
                    buyerProductCodes.getFirst(), listingCode);
            var genericBuyerRow = context.getBean(BuyerProductQueryService.class)
                    .page(buyerA, List.of(), 1, 20).getList().stream()
                    .filter(row -> buyerProductCodes.getFirst().equals(row.buyerProductCode())).findFirst().orElseThrow();
            assertThat(genericBuyerRow.suggestedSalePriceCents()).isEqualTo(10_000L);
            assertThat(genericBuyerRow.platformBasePriceCents()).isEqualTo(10_000L);
            assertThat(genericBuyerRow.distPriceCents()).isEqualTo(10_000L);
            var genericProjectRow = listingQuery.page(projectCode, 1, 20).getList().getFirst();
            assertThat(genericProjectRow.distPriceCents()).isEqualTo(10_000L);
            assertThat(genericProjectRow.finalPriceCents()).isEqualTo(frozenBeforeGeneric);
            listings.patchL3(projectCode, listingCode, L3AdjustMode.FOLLOW_L2, null, null, 1L);
            assertThat(listingQuery.page(projectCode, 1, 20).getList().getFirst().finalPriceCents())
                    .isEqualTo(10_000L);

            // 模拟现场新 SKU：只有通用定价版本，没有旧 L1；新增项目仍须成功并展示真正的 SKU 建议价。
            PlatformSkuDO genericSku = context.getBean(PlatformSkuMapper.class).selectByCode("SKU_001_100");
            context.getBean(PlatformSettlementPriceMapper.class).deleteById(genericSku.getId());
            String genericOnlyProject = projects.create(new CreateProjectCommand(
                    "通用定价项目", buyerA, "WELFARE",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    null, "验证无旧L1", "ACTIVE", 1L));
            long genericCandidateCount = buyerProductQuery.page(buyerA, List.of(), genericOnlyProject, 1, 1)
                    .getTotal();
            assertThat(genericCandidateCount).isPositive();
            String genericOnlyListing = listings.addProducts(genericOnlyProject,
                    List.of(new ListingLine("SKU_001_100", L3AdjustMode.FOLLOW_L2, null, null)), 1L).getFirst();
            assertThat(buyerProductQuery.page(buyerA, List.of(), genericOnlyProject, 1, 1).getTotal())
                    .isEqualTo(genericCandidateCount - 1);
            assertThat(listingQuery.page(genericOnlyProject, 1, 20).getList().getFirst())
                    .satisfies(row -> {
                        assertThat(row.listingCode()).isEqualTo(genericOnlyListing);
                        assertThat(row.suggestPriceCents()).isNull();
                        assertThat(row.suggestedSalePriceCents()).isEqualTo(10_000L);
                        assertThat(row.platformBasePriceCents()).isEqualTo(10_000L);
                        assertThat(row.distPriceCents()).isEqualTo(10_000L);
                        assertThat(row.finalPriceCents()).isEqualTo(10_000L);
                    });
            listings.retire(genericOnlyProject, genericOnlyListing, 1L);
            assertThat(buyerProductQuery.page(buyerA, List.of(), genericOnlyProject, 1, 1).getTotal())
                    .isEqualTo(genericCandidateCount);
            var retiredProjectRow = projectQueries.page(new ProjectAdminQueryService.ProjectPageQuery(
                    null, genericOnlyProject, List.of(), null, List.of(), null, null, 1, 20))
                    .getList().getFirst();
            assertThat(retiredProjectRow.skuCount()).isZero();
            assertThat(retiredProjectRow.projectPriceSkuCount()).isZero();

            assertThatThrownBy(() -> projects.update(new UpdateProjectCommand(
                    projectCode, "福利项目", buyerB, "WELFARE",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    null, "说明", null, 1L)))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(PROJECT_BUYER_IMMUTABLE.getCode());
            assertThat(L2_CENTS).isEqualTo(Math.round(L1_CENTS * (L2_PERCENT / 100.0d)) + L2_AMOUNT_CENTS);
        }
    }

    /**
     * 在真实 MySQL 上验证通用规则的原子保存、项目继承/覆盖、最低供货价重算及利润隐藏自动恢复。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#8-自动化测试与联调门禁}。
     */
    private static void verifyGenericBatchPricing(ConfigurableApplicationContext context,
                                                   String buyerA, String projectCode,
                                                   String buyerProductCode, String listingCode) {
        PlatformSkuDO sku = context.getBean(PlatformSkuMapper.class).selectByCode("SKU_001_100");
        sku.setMarketPrice(10_000L);
        sku.setSuggestedSalePrice(10_000L);
        assertThat(context.getBean(PlatformSkuMapper.class).updateById(sku)).isEqualTo(1);
        SupplierOfferDO offer = insertEffectiveOffer(context, sku, 8_000L);
        GenericBatchPricingService pricing = context.getBean(GenericBatchPricingService.class);
        PricingConfig config = new PricingConfig(directMarket(), directMarket(),
                new ServiceFeeRule(new BigDecimal("2"), FixedOperator.ADD,
                        new BigDecimal("1.00"), false, "服务费："),
                new HideRule(new BigDecimal("10"), new BigDecimal("5")),
                new TaxMetadata(false, "NON_TAXABLE", null));
        pricing.savePlatform(List.of(sku.getSkuCode()), config, 1L);
        GenericBatchPricingService.PlatformPricingDetail platformDetail =
                pricing.getPlatformPricing(sku.getSkuCode());
        assertThat(platformDetail.currentVersionId()).isNotNull();
        assertThat(platformDetail.versionNo()).isEqualTo(1);
        assertThat(platformDetail.pricingStatus()).isEqualTo(PricingStatus.READY.name());
        assertThat(platformDetail.distributionRule()).isEqualTo(config.distributionRule());
        assertThat(platformDetail.salesRule()).isEqualTo(config.salesRule());
        assertThat(platformDetail.serviceFeeRule().percentage())
                .isEqualByComparingTo(config.serviceFeeRule().percentage());
        assertThat(platformDetail.serviceFeeRule().operator()).isEqualTo(config.serviceFeeRule().operator());
        assertThat(platformDetail.serviceFeeRule().operand())
                .isEqualByComparingTo(config.serviceFeeRule().operand());
        assertThat(platformDetail.serviceFeeRule().hideCopy()).isEqualTo(config.serviceFeeRule().hideCopy());
        assertThat(platformDetail.serviceFeeRule().copy()).isEqualTo(config.serviceFeeRule().copy());
        assertThat(platformDetail.hideRule()).isEqualTo(config.hideRule());
        assertThat(platformDetail.taxMetadata().taxIncluded()).isFalse();
        assertThat(platformDetail.taxMetadata().taxRateCode()).isEqualTo("NON_TAXABLE");
        assertThat(platformDetail.taxMetadata().taxRateLabel()).isEqualTo("不征税");
        assertThat(platformDetail.marketPriceCents()).isEqualTo(10_000L);
        assertThat(platformDetail.supplyPriceCents()).isEqualTo(8_000L);
        assertThat(platformDetail.distributionPriceCents()).isEqualTo(10_000L);
        pricing.saveBuyer(buyerA, List.of(buyerProductCode),
                GenericBatchPricingService.ProjectOverrides.allInherited(), 1L);

        var buyerProduct = context.getBean(BuyerProductMapper.class).selectByCode(buyerProductCode);
        var buyerIdentity = context.getBean(com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper.class)
                .selectByBuyerProductId(buyerProduct.getId());
        var buyerVersion = context.getBean(com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper.class)
                .selectById(buyerIdentity.getCurrentVersionId());
        assertThat(buyerVersion.getSupplyPriceCents()).isEqualTo(8_000L);
        assertThat(buyerVersion.getDistributionPriceCents()).isEqualTo(10_000L);
        assertThat(buyerIdentity.getPricingStatus()).isEqualTo(PricingStatus.READY.name());

        // 新增项目商品行内覆盖须在同一事务落成项目版本，列表与详情按项目当前分销价回显。
        String inlineProjectCode = context.getBean(ProjectAdminCommandService.class).create(new CreateProjectCommand(
                "行内专属价项目", buyerA, "WELFARE",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                null, "行内定价回归", "ACTIVE", 1L));
        FormulaRule inlineRule = new FormulaRule(PriceMode.CUSTOM, PriceBase.MARKET_PRICE,
                new BigDecimal("90"), FixedOperator.ADD, BigDecimal.ZERO);
        var inlineOverrides = new GenericBatchPricingService.ProjectOverrides(false, inlineRule,
                true, null, true, null, true, null, true, null);
        String inlineListingCode = context.getBean(ProjectListingCommandService.class).addProducts(
                inlineProjectCode, List.of(new ListingLine(sku.getSkuCode(), L3AdjustMode.FOLLOW_L2,
                        null, null, inlineOverrides)), 1L).getFirst();
        ProjectListingDO inlineListing = context.getBean(ProjectListingMapper.class).selectByCode(inlineListingCode);
        var inlinePricingMapper = context.getBean(
                com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingMapper.class);
        var inlineVersionMapper = context.getBean(
                com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingVersionMapper.class);
        var inlineIdentity = inlinePricingMapper.selectByListingId(inlineListing.getId());
        assertThat(inlineVersionMapper.selectById(inlineIdentity.getCurrentVersionId()).getDistributionPriceCents())
                .isEqualTo(9_000L);
        var inlineRow = context.getBean(ProjectListingQueryService.class)
                .page(inlineProjectCode, 1, 20).getList().getFirst();
        assertThat(inlineRow.projectPriceCents()).isEqualTo(9_000L);
        assertThat(inlineRow.finalPriceCents()).isEqualTo(9_000L);
        assertThat(inlineRow.priceSource()).isEqualTo(PriceSource.L3.name());
        assertThat(inlineRow.projectPriceCovered()).isTrue();
        assertThat(context.getBean(ProjectAdminQueryService.class).get(inlineProjectCode).projectPriceSkuCount())
                .isEqualTo(1);
        var projectPage = context.getBean(ProjectAdminQueryService.class).page(
                new ProjectAdminQueryService.ProjectPageQuery(
                        null, null, List.of(), null, List.of(), null, null, 1, 50));
        var inlineProjectRow = projectPage.getList().stream()
                .filter(row -> inlineProjectCode.equals(row.projectCode())).findFirst().orElseThrow();
        assertThat(inlineProjectRow.skuCount()).isEqualTo(1);
        assertThat(inlineProjectRow.projectPriceSkuCount()).isEqualTo(1);

        ProjectListingDO listing = context.getBean(ProjectListingMapper.class).selectByCode(listingCode);
        var projectPricingMapper = context.getBean(
                com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingMapper.class);
        var projectVersionMapper = context.getBean(
                com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingVersionMapper.class);
        var inheritedIdentity = projectPricingMapper.selectByListingId(listing.getId());
        var inheritedVersion = projectVersionMapper.selectById(inheritedIdentity.getCurrentVersionId());
        assertThat(inheritedVersion.getBuyerPricingVersionId()).isEqualTo(buyerVersion.getId());
        assertThat(inheritedVersion.getSalesPriceCents()).isEqualTo(10_000L);
        ObjectMapper responseMapper = context.getBean(ObjectMapper.class);
        JsonNode inheritedConfig = responseMapper.valueToTree(context.getBean(ProjectListingQueryService.class)
                .page(projectCode, 1, 20).getList().getFirst().projectPricingConfig());
        assertThat(inheritedConfig)
                .isEqualTo(responseMapper.valueToTree(GenericBatchPricingService.ProjectOverrides.allInherited()));

        Long currentBeforeRejectedBatch = buyerIdentity.getCurrentVersionId();
        assertThatThrownBy(() -> pricing.saveBuyer(buyerA,
                List.of(buyerProductCode, "BPD_NOT_BELONG_TO_CURRENT_BUYER"),
                GenericBatchPricingService.ProjectOverrides.allInherited(), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(context.getBean(com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper.class)
                .selectByBuyerProductId(buyerProduct.getId()).getCurrentVersionId())
                .isEqualTo(currentBeforeRejectedBatch);

        FormulaRule projectSales = new FormulaRule(PriceMode.CUSTOM, PriceBase.MARKET_PRICE,
                new BigDecimal("120"), FixedOperator.ADD, BigDecimal.ZERO);
        var savedOverrides = new GenericBatchPricingService.ProjectOverrides(true, null, false, projectSales,
                true, null, true, null, true, null);
        pricing.saveProject(projectCode, List.of(listingCode), savedOverrides, 2L);
        var overriddenIdentity = projectPricingMapper.selectByListingId(listing.getId());
        assertThat(projectVersionMapper.selectById(overriddenIdentity.getCurrentVersionId()).getSalesPriceCents())
                .isEqualTo(12_000L);
        JsonNode savedConfig = responseMapper.valueToTree(context.getBean(ProjectListingQueryService.class)
                .page(projectCode, 1, 20).getList().getFirst().projectPricingConfig());
        assertThat(savedConfig).isEqualTo(responseMapper.valueToTree(savedOverrides));

        publishSupplyPrice(context, offer, 9_500L, 2);
        pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(sku.getId()));
        assertThat(projectPricingMapper.selectByListingId(listing.getId()).getPricingStatus())
                .isEqualTo(PricingStatus.MARGIN_HIDDEN.name());

        publishSupplyPrice(context, offer, 7_000L, 3);
        pricing.recalculatePlatformSku(new PricingDependencyChangedEvent(sku.getId()));
        assertThat(projectPricingMapper.selectByListingId(listing.getId()).getPricingStatus())
                .isEqualTo(PricingStatus.READY.name());
        JsonNode recalculatedConfig = responseMapper.valueToTree(context.getBean(ProjectListingQueryService.class)
                .page(projectCode, 1, 20).getList().getFirst().projectPricingConfig());
        assertThat(recalculatedConfig).isEqualTo(responseMapper.valueToTree(savedOverrides));
    }

    private static FormulaRule directMarket() {
        return new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null);
    }

    private static SupplierOfferDO insertEffectiveOffer(ConfigurableApplicationContext context,
                                                         PlatformSkuDO sku, long price) {
        LocalDateTime now = context.getBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class).now();
        SupplierDO supplier = SupplierDO.builder().supplierCode("PRICING_SUPPLIER")
                .supplierName("定价供应商").shortName("定价供应商").supplierType("STANDARD_SUPPLIER")
                .status(SupplierStatus.ACTIVE.getCode()).version(0L).createdBy(1L).createdAt(now)
                .updatedBy(1L).updatedAt(now).build();
        context.getBean(SupplierMapper.class).insert(supplier);
        SupplierOfferDO offer = SupplierOfferDO.builder().offerNo("OFR_PRICING")
                .offerCode("SKU_001_100_PRICING").supplierId(supplier.getId()).platformSkuId(sku.getId())
                .status(OfferStatus.PUBLISHED.getCode()).version(0L).createdBy(1L).createdAt(now)
                .updatedBy(1L).updatedAt(now).build();
        context.getBean(SupplierOfferMapper.class).insert(offer);
        publishSupplyPrice(context, offer, price, 1);
        return offer;
    }

    private static void publishSupplyPrice(ConfigurableApplicationContext context, SupplierOfferDO offer,
                                           long price, int versionNo) {
        LocalDateTime now = context.getBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class).now();
        SupplierOfferVersionDO version = SupplierOfferVersionDO.builder().offerId(offer.getId())
                .versionNo(versionNo).externalSkuCode("EXT-MOVIE-100-V" + versionNo)
                .supplierSettlementUnitPrice(price).currency("CNY").connectorVersionId(1L)
                .inventorySourceId(1L).minQuantity(1L).maxQuantity(100L).quantityStep(1L)
                .primaryCapabilityCode("DIRECT_RECHARGE").completionMode("SYNCHRONOUS")
                .createIdempotencyMode("DEDUP_ONLY").unknownResultPolicy("MANUAL")
                .partialDeliveryMode("FORBIDDEN").resultMultiplicity("SINGLE")
                .fulfillmentConfigSchemaVersion(1).fulfillmentConfigJson("{}")
                .validFrom(now.minusMinutes(1)).validTo(null).publishReason("定价集成测试")
                .publishedBy(1L).publishedAt(now).createdAt(now).build();
        context.getBean(SupplierOfferVersionMapper.class).insert(version);
        offer.setCurrentVersionId(version.getId());
        offer.setUpdatedAt(now);
        context.getBean(SupplierOfferMapper.class).updateById(offer);
    }

    /**
     * 在真实 MySQL 事务与行锁上验证 L2/L3 批量定价，不以 Mock Mapper 代替数据库原子性。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#6-测试策略}。
     */
    private static void verifyBatchPricingDatabaseSemantics(ConfigurableApplicationContext context,
                                                             String buyerA, String buyerB,
                                                             String projectA, String buyerProductA100Code,
                                                             String listingA) throws Exception {
        BuyerProductCommandService products = context.getBean(BuyerProductCommandService.class);
        BuyerProductMapper productMapper = context.getBean(BuyerProductMapper.class);
        BuyerProductL2OverrideMapper l2OverrideMapper = context.getBean(BuyerProductL2OverrideMapper.class);
        BuyerProductL2OverrideVersionMapper l2VersionMapper =
                context.getBean(BuyerProductL2OverrideVersionMapper.class);
        ProjectAdminCommandService projects = context.getBean(ProjectAdminCommandService.class);
        ProjectListingCommandService listings = context.getBean(ProjectListingCommandService.class);
        ProjectListingQueryService listingQuery = context.getBean(ProjectListingQueryService.class);
        ProjectListingMapper listingMapper = context.getBean(ProjectListingMapper.class);
        ProjectListingVersionMapper listingVersionMapper = context.getBean(ProjectListingVersionMapper.class);

        insertL1(context, "SKU_001_200", 19_800L);
        String buyerProductA200Code = products.addProducts(buyerA, List.of(
                new ProductLine("SKU_001_200", L2AdjustMode.FOLLOW_TEMPLATE, null, null, null, null)), 1L).getFirst();
        String buyerProductB100Code = products.addProducts(buyerB, List.of(
                new ProductLine("SKU_001_100", L2AdjustMode.FOLLOW_TEMPLATE, null, null, null, null)), 1L).getFirst();
        BuyerProductQueryService buyerProductQuery = context.getBean(BuyerProductQueryService.class);
        var selection = buyerProductQuery.page(buyerA, List.of("MOVIE"), projectA,
                "电影票", "CAT_001", 1, 1);
        assertThat(selection.getTotal()).isEqualTo(1L);
        assertThat(selection.getList()).extracting(BuyerProductQueryService.BuyerProductListItem::buyerProductCode)
                .containsExactly(buyerProductA200Code);
        assertThat(buyerProductQuery.page(buyerA, List.of("MOVIE"), projectA,
                "SKU_001_200", "CAT_002_001", 1, 1).getTotal()).isEqualTo(1L);
        assertThat(buyerProductQuery.page(buyerA, List.of("MOVIE"), projectA,
                "电影票", "CAT_001", 2, 1).getList()).isEmpty();
        assertThat(buyerProductQuery.page(buyerA, List.of("MOVIE"), projectA,
                "电影票", "UNKNOWN", 1, 1).getTotal()).isZero();
        BuyerProductDO buyerProductA100 = productMapper.selectByCode(buyerProductA100Code);
        BuyerProductDO buyerProductA200 = productMapper.selectByCode(buyerProductA200Code);

        BuyerProductL2OverrideDO l2BeforePreview =
                l2OverrideMapper.selectByBuyerProductId(buyerProductA100.getId());
        int l2VersionCountBeforePreview = l2VersionMapper
                .selectByBuyerProductId(buyerProductA100.getId()).size();
        var l2Previews = products.previewL2Batch(buyerA,
                List.of(buyerProductA100Code, buyerProductA200Code), L2AdjustMode.CUSTOM, 80, 20L);
        assertThat(l2Previews).hasSize(2);
        assertThat(l2Previews.getFirst().currentL2PriceCents()).isEqualTo(L2_CENTS);
        assertThat(l2Previews.getFirst().previewL2PriceCents()).isEqualTo(7_860L);
        assertThat(l2Previews.get(1).currentL2PriceCents()).isEqualTo(19_800L);
        assertThat(l2Previews.get(1).previewL2PriceCents()).isEqualTo(15_860L);
        assertThat(l2VersionMapper.selectByBuyerProductId(buyerProductA100.getId()))
                .hasSize(l2VersionCountBeforePreview);
        assertThat(l2OverrideMapper.selectByBuyerProductId(buyerProductA100.getId()).getCurrentVersionId())
                .isEqualTo(l2BeforePreview.getCurrentVersionId());
        assertThat(l2OverrideMapper.selectByBuyerProductId(buyerProductA200.getId())).isNull();

        Integer thresholdBeforeRollback = buyerProductA100.getDisplayThresholdPercent();
        assertThatThrownBy(() -> products.patchL2Batch(buyerA,
                List.of(buyerProductA100Code, buyerProductB100Code),
                L2AdjustMode.CUSTOM, 70, 0L, 9, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_PRODUCT_NOT_FOUND.getCode());
        assertThat(l2VersionMapper.selectByBuyerProductId(buyerProductA100.getId()))
                .hasSize(l2VersionCountBeforePreview);
        assertThat(l2OverrideMapper.selectByBuyerProductId(buyerProductA100.getId()).getCurrentVersionId())
                .isEqualTo(l2BeforePreview.getCurrentVersionId());
        assertThat(productMapper.selectByCode(buyerProductA100Code).getDisplayThresholdPercent())
                .isEqualTo(thresholdBeforeRollback);

        products.patchL2Batch(buyerA, List.of(buyerProductA100Code, buyerProductA200Code),
                L2AdjustMode.CUSTOM, 80, 20L, 7, 1L);
        assertCurrentL2(l2OverrideMapper, l2VersionMapper, productMapper.selectByCode(buyerProductA100Code),
                L2AdjustMode.CUSTOM, 7_860L);
        assertCurrentL2(l2OverrideMapper, l2VersionMapper, productMapper.selectByCode(buyerProductA200Code),
                L2AdjustMode.CUSTOM, 15_860L);
        assertThat(productMapper.selectByCode(buyerProductA100Code).getDisplayThresholdPercent()).isEqualTo(7);
        assertThat(productMapper.selectByCode(buyerProductA200Code).getDisplayThresholdPercent()).isEqualTo(7);

        products.patchL2Batch(buyerA, List.of(buyerProductA200Code),
                L2AdjustMode.EQUAL_L1, null, null, null, 1L);
        assertCurrentL2(l2OverrideMapper, l2VersionMapper, productMapper.selectByCode(buyerProductA200Code),
                L2AdjustMode.EQUAL_L1, 19_800L);
        int a200HistorySize = l2VersionMapper.selectByBuyerProductId(buyerProductA200.getId()).size();
        products.patchL2Batch(buyerA, List.of(buyerProductA200Code),
                L2AdjustMode.FOLLOW_TEMPLATE, null, null, null, 1L);
        assertThat(l2OverrideMapper.selectByBuyerProductId(buyerProductA200.getId()).getCurrentVersionId()).isNull();
        assertThat(l2VersionMapper.selectByBuyerProductId(buyerProductA200.getId())).hasSize(a200HistorySize);

        String projectB = projects.create(new CreateProjectCommand(
                "福利项目B", buyerA, "WELFARE",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                null, "说明", "ACTIVE", 1L));
        String listingB = listings.addProducts(projectB, List.of(
                new ListingLine("SKU_001_100", L3AdjustMode.FOLLOW_L2, null, null)), 1L).getFirst();
        ProjectListingDO listingABeforePreview = listingMapper.selectByCode(listingA);
        int listingAVersionCountBeforePreview = listingVersionMapper
                .selectByListingId(listingABeforePreview.getId()).size();
        var l3Previews = listings.previewL3Batch(projectA, List.of(listingA),
                L3AdjustMode.EQUAL_L1, null, null);
        assertThat(l3Previews).singleElement().satisfies(preview -> {
            assertThat(preview.currentFinalPriceCents()).isEqualTo(8_820L);
            assertThat(preview.previewFinalPriceCents()).isEqualTo(L1_CENTS);
            assertThat(preview.previewPriceSource()).isEqualTo(PriceSource.L3);
        });
        assertThat(listingVersionMapper.selectByListingId(listingABeforePreview.getId()))
                .hasSize(listingAVersionCountBeforePreview);
        assertThat(listingMapper.selectByCode(listingA).getCurrentVersionId())
                .isEqualTo(listingABeforePreview.getCurrentVersionId());

        ProjectListingDO listingBBeforeRollback = listingMapper.selectByCode(listingB);
        assertThatThrownBy(() -> listings.patchL3Batch(projectA, List.of(listingA, listingB),
                L3AdjustMode.CUSTOM, 70, 0L, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_LISTING_NOT_FOUND.getCode());
        assertThat(listingVersionMapper.selectByListingId(listingABeforePreview.getId()))
                .hasSize(listingAVersionCountBeforePreview);
        assertThat(listingMapper.selectByCode(listingA).getCurrentVersionId())
                .isEqualTo(listingABeforePreview.getCurrentVersionId());
        assertThat(listingMapper.selectByCode(listingB).getCurrentVersionId())
                .isEqualTo(listingBBeforeRollback.getCurrentVersionId());

        listings.patchL3Batch(projectA, List.of(listingA), L3AdjustMode.CUSTOM, 75, 0L, 1L);
        listings.patchL3Batch(projectB, List.of(listingB), L3AdjustMode.EQUAL_L1, null, null, 1L);
        assertThat(listingQuery.page(projectA, 1, 20).getList().getFirst().finalPriceCents()).isEqualTo(7_350L);
        assertThat(listingQuery.page(projectB, 1, 20).getList().getFirst().finalPriceCents()).isEqualTo(L1_CENTS);

        listings.patchL3Batch(projectB, List.of(listingB), L3AdjustMode.FOLLOW_L2, null, null, 1L);
        ProjectListingDO frozenListingB = listingMapper.selectByCode(listingB);
        Long frozenListingBVersionId = frozenListingB.getCurrentVersionId();
        assertThat(listingVersionMapper.selectById(frozenListingBVersionId).getBuyerSettlementUnitPrice())
                .isEqualTo(7_860L);
        products.patchL2Batch(buyerA, List.of(buyerProductA100Code),
                L2AdjustMode.CUSTOM, 70, 0L, null, 1L);
        assertThat(listingMapper.selectByCode(listingB).getCurrentVersionId()).isEqualTo(frozenListingBVersionId);
        assertThat(listingVersionMapper.selectById(frozenListingBVersionId).getBuyerSettlementUnitPrice())
                .isEqualTo(7_860L);
        var projectBRow = listingQuery.page(projectB, 1, 20).getList().getFirst();
        assertThat(projectBRow.distPriceCents()).isEqualTo(6_860L);
        assertThat(projectBRow.finalPriceCents()).isEqualTo(7_860L);
        assertThat(projectBRow.priceSource()).isEqualTo(PriceSource.L2.name());

        verifyConcurrentProjectPricing(listings, listingMapper, listingVersionMapper, projectA, listingA);
    }

    private static void assertCurrentL2(BuyerProductL2OverrideMapper overrideMapper,
                                        BuyerProductL2OverrideVersionMapper versionMapper,
                                        BuyerProductDO product, L2AdjustMode expectedMode,
                                        long expectedPriceCents) {
        BuyerProductL2OverrideDO identity = overrideMapper.selectByBuyerProductId(product.getId());
        BuyerProductL2OverrideVersionDO version = versionMapper.selectById(identity.getCurrentVersionId());
        assertThat(version.getAdjustMode()).isEqualTo(expectedMode.name());
        assertThat(version.getResolvedUnitPriceCents()).isEqualTo(expectedPriceCents);
    }

    private static void verifyConcurrentProjectPricing(ProjectListingCommandService listings,
                                                       ProjectListingMapper listingMapper,
                                                       ProjectListingVersionMapper listingVersionMapper,
                                                       String projectCode, String listingCode) throws Exception {
        ProjectListingDO before = listingMapper.selectByCode(listingCode);
        List<ProjectListingVersionDO> beforeVersions = listingVersionMapper.selectByListingId(before.getId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                ready.countDown();
                await(start);
                listings.patchL3Batch(projectCode, List.of(listingCode),
                        L3AdjustMode.CUSTOM, 60, 0L, 11L);
            });
            Future<?> second = executor.submit(() -> {
                ready.countDown();
                await(start);
                listings.patchL3Batch(projectCode, List.of(listingCode),
                        L3AdjustMode.CUSTOM, 55, 0L, 12L);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        List<ProjectListingVersionDO> afterVersions = listingVersionMapper.selectByListingId(before.getId());
        assertThat(afterVersions).hasSize(beforeVersions.size() + 2);
        for (int index = 0; index < afterVersions.size(); index++) {
            assertThat(afterVersions.get(index).getVersionNo()).isEqualTo(index + 1);
        }
        ProjectListingDO after = listingMapper.selectByCode(listingCode);
        ProjectListingVersionDO current = listingVersionMapper.selectById(after.getCurrentVersionId());
        assertThat(current.getId()).isEqualTo(afterVersions.getLast().getId());
        assertThat(current.getBuyerSettlementUnitPrice()).isIn(5_390L, 5_880L);
        assertThat(current.getSettlementPriceLevel()).isEqualTo(PriceSource.L3.name());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发定价起跑信号超时");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发定价起跑信号被中断", interrupted);
        }
    }

    private static void insertL1(ConfigurableApplicationContext context, String skuCode, long cents) {
        PlatformSkuMapper skuMapper = context.getBean(PlatformSkuMapper.class);
        PlatformSettlementPriceMapper l1Mapper = context.getBean(PlatformSettlementPriceMapper.class);
        PlatformSettlementPriceVersionMapper l1VersionMapper =
                context.getBean(PlatformSettlementPriceVersionMapper.class);
        PlatformSkuDO sku = skuMapper.selectByCode(skuCode);
        assertThat(sku).isNotNull();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PlatformSettlementPriceDO identity = PlatformSettlementPriceDO.builder()
                .platformSkuId(sku.getId())
                .version(0L)
                .updatedAt(now)
                .build();
        l1Mapper.insert(identity);
        PlatformSettlementPriceVersionDO version = PlatformSettlementPriceVersionDO.builder()
                .platformSkuId(sku.getId())
                .versionNo(1)
                .unitPriceCents(cents)
                .currency(Money.CURRENCY_CNY)
                .publishedBy(1L)
                .publishedAt(now)
                .createdAt(now)
                .build();
        l1VersionMapper.insert(version);
        identity.setCurrentVersionId(version.getId());
        identity.setUpdatedAt(now);
        l1Mapper.updateById(identity);
    }

    private static void seedCatalog(ProductCatalogCommandService catalog) {
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "BRD_001", "影院品牌", null, null, 0, 1L));
        catalog.changeBrandStatus("BRD_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_001", "权益商品", null, 0, 1L));
        catalog.changeCategoryStatus("CAT_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_001_001", "卡券", "CAT_001", 0, 1L));
        catalog.changeCategoryStatus("CAT_001_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "CAT_002_001", "电影快充", "CAT_001_001", 0, 1L));
        catalog.changeCategoryStatus("CAT_002_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "SPU_001", "BRD_001", "CAT_002_001",
                "MOVIE", "MOVIE", "MOVIE_TICKET",
                "电影票", null, null, null, 1L));
        catalog.changeSpuStatus("SPU_001", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "SKU_001_100", "SPU_001", "电影票 100", FulfillmentType.DIRECT_RECHARGE.name(),
                10_000L, Money.CURRENCY_CNY, 1L));
        catalog.changeSkuStatus("SKU_001_100", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "SKU_001_200", "SPU_001", "电影票 200", FulfillmentType.DIRECT_RECHARGE.name(),
                20_000L, Money.CURRENCY_CNY, 1L));
        catalog.changeSkuStatus("SKU_001_200", CatalogEntityStatus.ACTIVE, 1L);
    }

    private static CreateBuyerCommand createBuyer(String name, String legalEntity, String creditCode) {
        return new CreateBuyerCommand(
                name, List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                null, 1, "ENTERPRISE", null, "MONTHLY", 7, List.of(), List.of(),
                LocalDateTime.of(2026, 9, 1, 9, 30), null, legalEntity, creditCode, "李四", "13900139000",
                "上海市浦东新区", "测试银行", "6222021234567890",
                null, null, null, null, List.of(), List.of(), List.of("MOVIE"), List.of("MOVIE"),
                0L, 1L, "project-admin-it");
    }

    private ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.security.key-version", "test-v1");
        properties.put("aifuli.supply-chain.security.envelope-key-base64", KEY_BASE64);
        properties.put("aifuli.supply-chain.security.fingerprint-key-base64", KEY_BASE64);
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
            AesGcmEnvelopeCrypto.class, ProductCatalogCommandService.class, ProductCenterTestConfiguration.class,
            SettlementPriceResolver.class,
            BuyerAccountOpeningService.class, AccountLedgerWriter.class,
            BuyerAdminCommandService.class, BuyerAdminQueryService.class,
            BuyerProductCommandService.class, BuyerProductQueryService.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductSelectionBatchTransactionalWorker.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogIdentityWriter.class,
            com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogIdentityProbe.class,
            ProjectAdminCommandService.class, ProjectAdminQueryService.class,
            ProjectListingCommandService.class, ProjectListingQueryService.class, ProjectListingPriceSource.class,
            GenericBatchPricingService.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        TaxRateDictionaryAdapter taxRateDictionaryAdapter() {
            TaxRateDictionaryAdapter adapter = mock(TaxRateDictionaryAdapter.class);
            when(adapter.requireEnabled(any(), anyString())).thenAnswer(invocation ->
                    new TaxMetadata(invocation.getArgument(0), invocation.getArgument(1), "不征税"));
            return adapter;
        }
    }
}
