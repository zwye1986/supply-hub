package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.supply.application.OfferFulfillmentScoreService;
import com.aifuli.module.supplychain.tradecore.supply.application.OfferFulfillmentScoreService.SkuSupplierScoreLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.DraftLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.OfferListItem;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.CreateSupplierProfileCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService.ScopeLine;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.MySQLContainer;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 履约能力评分双向读写同一 Offer 身份列：供应商 PUT 后商品 GET 一致，反向亦然。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md#8-测试门禁}。</p>
 */
class OfferFulfillmentScoreIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

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
    void supplierAndProductReadWriteTheSameOfferScores() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            SupplierProfileCommandService command = context.getBean(SupplierProfileCommandService.class);
            SupplierSupplyScopeService scopes = context.getBean(SupplierSupplyScopeService.class);
            SupplierCooperativeProductService offers = context.getBean(SupplierCooperativeProductService.class);
            OfferFulfillmentScoreService scores = context.getBean(OfferFulfillmentScoreService.class);
            ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
            SupplierOfferMapper offerMapper = context.getBean(SupplierOfferMapper.class);

            String supplierCode = command.create(createCommand("评分供应商", "91110000921000001X"));
            seedCatalog(catalog);
            scopes.replaceAll(supplierCode, List.of(new ScopeLine(
                    "MOVIE", "MOVIE", "MOVIE_TICKET", List.of("API_DIRECT"), 100L, 1L)), 1L);
            List<String> offerNos = offers.createPublished(supplierCode, List.of(
                    new DraftLine("MOVIE_100", "FIXED", 9_800L, null, null, "API_DIRECT", 100L, 1L),
                    new DraftLine("MOVIE_200", "FIXED", 19_800L, null, null, "API_DIRECT", 50L, 1L)), 1L);
            assertThat(offerNos).hasSize(2);

            SupplierOfferDO created = offerMapper.selectByNo(offerNos.getFirst());
            assertThat(created.getStabilityScore()).isEqualTo(80);
            assertThat(created.getTimelinessScore()).isEqualTo(80);

            int batchUpdated = scores.saveSupplierScores(supplierCode, 80, 70, null, 1L);
            assertThat(batchUpdated).isEqualTo(2);

            var skuView = scores.getSkuScores("MOVIE_100");
            assertThat(skuView.items()).hasSize(1);
            assertThat(skuView.items().getFirst().supplierCode()).isEqualTo(supplierCode);
            assertThat(skuView.items().getFirst().stabilityScore()).isEqualTo(80);
            assertThat(skuView.items().getFirst().timelinessScore()).isEqualTo(70);

            int skuUpdated = scores.saveSkuScores("MOVIE_100", List.of(
                    new SkuSupplierScoreLine(supplierCode, 90, 91)), 1L);
            assertThat(skuUpdated).isEqualTo(1);

            assertThat(scores.saveSupplierScores(supplierCode, null, 65,
                    List.of("MOVIE_100"), 1L)).isEqualTo(1);
            assertThat(scores.getSkuScores("MOVIE_100").items().getFirst().stabilityScore()).isEqualTo(90);
            assertThat(scores.getSkuScores("MOVIE_100").items().getFirst().timelinessScore()).isEqualTo(65);

            var remainingPage = offers.page(supplierCode, null, null, null, 1, 20);
            OfferListItem movie100 = remainingPage.getList().stream()
                    .filter(item -> "MOVIE_100".equals(item.platformSkuCode()))
                    .findFirst()
                    .orElseThrow();
            OfferListItem movie200 = remainingPage.getList().stream()
                    .filter(item -> "MOVIE_200".equals(item.platformSkuCode()))
                    .findFirst()
                    .orElseThrow();
            assertThat(movie100.stabilityScore()).isEqualTo(90);
            assertThat(movie100.timelinessScore()).isEqualTo(65);
            assertThat(movie200.stabilityScore()).isEqualTo(80);
            assertThat(movie200.timelinessScore()).isEqualTo(70);

            offers.updateCommercial(supplierCode, movie100.offerNo(),
                    new DraftLine("MOVIE_100", "FIXED", 8_800L, null, null, "API_DIRECT", 80L, 1L), 1L);
            assertThat(scores.getSkuScores("MOVIE_100").items().getFirst().stabilityScore()).isEqualTo(90);

            offers.retire(supplierCode, movie200.offerNo(), 1L);
            assertThat(scores.getSkuScores("MOVIE_200").items()).isEmpty();
            assertThat(scores.saveSupplierScores(supplierCode, 60, 50, null, 1L)).isEqualTo(1);
            assertThat(offerMapper.selectByNo(movie200.offerNo()).getStabilityScore()).isEqualTo(80);

            String otherSupplier = command.create(createCommand("旁路供应商", "91110000921000002X"));
            assertThatThrownBy(() -> scores.saveSkuScores("MOVIE_100", List.of(
                    new SkuSupplierScoreLine(otherSupplier, 10, 10)), 1L))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING.getCode());
        }
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
                10_000L, "CNY", 1L));
        catalog.changeSkuStatus("MOVIE_100", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "MOVIE_200", "MOVIE_TICKET", "电影票 200", FulfillmentType.DIRECT_RECHARGE.name(),
                20_000L, "CNY", 1L));
        catalog.changeSkuStatus("MOVIE_200", CatalogEntityStatus.ACTIVE, 1L);
    }

    private static CreateSupplierProfileCommand createCommand(String name, String creditCode) {
        return new CreateSupplierProfileCommand(
                name,
                List.of(new ContactLine("张三", "13800138000", "a@b.c")),
                "NATIONWIDE",
                List.of(),
                Boolean.FALSE,
                null,
                null,
                null,
                7,
                null,
                java.time.LocalDateTime.of(1970, 1, 1, 0, 0),
                SupplierStatus.ACTIVE,
                null,
                "测试科技有限公司",
                creditCode,
                "李四",
                "0005",
                "13900139000",
                "上海市浦东新区",
                "测试银行",
                "6222021234567890",
                "license-key-1",
                "id-front-key",
                "id-back-key",
                null,
                null,
                null,
                0L,
                1L,
                "offer-score-it");
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
            SupplierProfileCommandService.class, SupplierSupplyScopeService.class,
            SupplierCooperativeProductService.class, OfferFulfillmentScoreService.class})
    static class TestConfiguration {
    }
}
