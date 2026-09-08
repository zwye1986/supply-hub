package com.aifuli.module.supplychain.tradecore.buyer;

import com.aifuli.framework.common.exception.ServiceException;
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
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.ProductLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingCommandService.ListingLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.SettlementPriceResolver;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
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
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_BUYER_IMMUTABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_SKU_NOT_IN_BUYER_CATALOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目商品必须先是分销商品；冻结价不随后续 L2 回放；启用后归属不可改。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
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
    void projectSkuRequiresBuyerProductAndFreezeIgnoresLaterL2() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BuyerAdminCommandService buyers = context.getBean(BuyerAdminCommandService.class);
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
            insertL1(context, "MOVIE_100", L1_CENTS);

            List<String> buyerProductCodes = products.addProducts(buyerA, List.of(
                    new ProductLine("MOVIE_100", L2AdjustMode.FOLLOW_TEMPLATE, null, null, null)), 1L);
            assertThat(buyerProductCodes).hasSize(1);

            String projectCode = projects.create(new CreateProjectCommand(
                    "福利项目", buyerA, "WELFARE",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    null, "说明", "ACTIVE", 1L));
            assertThat(projectCode).startsWith("PRJ_");

            assertThatThrownBy(() -> listings.addProducts(projectCode, List.of(
                    new ListingLine("MOVIE_200", L3AdjustMode.FOLLOW_L2, null, null)), 1L))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(PROJECT_SKU_NOT_IN_BUYER_CATALOG.getCode());

            List<String> listingCodes = listings.addProducts(projectCode, List.of(
                    new ListingLine("MOVIE_100", L3AdjustMode.FOLLOW_L2, null, null)), 1L);
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

            assertThatThrownBy(() -> projects.update(new UpdateProjectCommand(
                    projectCode, "福利项目", buyerB, "WELFARE",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    null, "说明", 1L)))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(PROJECT_BUYER_IMMUTABLE.getCode());
            assertThat(L2_CENTS).isEqualTo(Math.round(L1_CENTS * (L2_PERCENT / 100.0d)) + L2_AMOUNT_CENTS);
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
                "MOVIE_BRAND", "影院品牌", null, null, 0, 1L));
        catalog.changeBrandStatus("MOVIE_BRAND", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "RIGHTS", "权益商品", null, 0, 1L));
        catalog.changeCategoryStatus("RIGHTS", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "VOUCHER", "卡券", "RIGHTS", 0, 1L));
        catalog.changeCategoryStatus("VOUCHER", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "MOVIE", "电影快充", "VOUCHER", 0, 1L));
        catalog.changeCategoryStatus("MOVIE", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "MOVIE_TICKET", "MOVIE_BRAND", "MOVIE",
                "MOVIE", "MOVIE", "MOVIE_TICKET",
                "电影票", null, null, null, 1L));
        catalog.changeSpuStatus("MOVIE_TICKET", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "MOVIE_100", "MOVIE_TICKET", "电影票 100", FulfillmentType.DIRECT_RECHARGE.name(),
                10_000L, Money.CURRENCY_CNY, "DIRECT_RECHARGE_TARGET", 1,
                "DIRECT_RECHARGE_RESULT", 1, 1L));
        catalog.changeSkuStatus("MOVIE_100", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "MOVIE_200", "MOVIE_TICKET", "电影票 200", FulfillmentType.DIRECT_RECHARGE.name(),
                20_000L, Money.CURRENCY_CNY, "DIRECT_RECHARGE_TARGET", 1,
                "DIRECT_RECHARGE_RESULT", 1, 1L));
        catalog.changeSkuStatus("MOVIE_200", CatalogEntityStatus.ACTIVE, 1L);
    }

    private static CreateBuyerCommand createBuyer(String name, String legalEntity, String creditCode) {
        return new CreateBuyerCommand(
                name, List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                null, 1, "ENTERPRISE", null, "MONTHLY", 7, List.of(), List.of(),
                null, null, legalEntity, creditCode, "李四", "13900139000",
                "上海市浦东新区", "测试银行", "6222021234567890",
                null, null, null, null, List.of(), List.of(), List.of(), List.of(),
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
            AesGcmEnvelopeCrypto.class, ProductCatalogCommandService.class,
            SettlementPriceResolver.class,
            BuyerAccountOpeningService.class, AccountLedgerWriter.class,
            BuyerAdminCommandService.class, BuyerAdminQueryService.class,
            BuyerProductCommandService.class, BuyerProductQueryService.class,
            ProjectAdminCommandService.class, ProjectAdminQueryService.class,
            ProjectListingCommandService.class, ProjectListingQueryService.class})
    static class TestConfiguration {
    }
}
