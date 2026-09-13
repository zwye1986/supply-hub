package com.aifuli.module.supplychain.tradecore.catalog;

import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogChangeBatchRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogChangesReqDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogItemRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogPageReqDTO;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerCatalogQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerListingPublicationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
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
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasterDataLifecycleIntegrationTest {

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
    void publishesImmutableListingVersionsAndExposesBuyerOnlyCatalogProjection() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            BuyerCommandService buyerService = context.getBean(BuyerCommandService.class);
            ProductCatalogCommandService catalogService = context.getBean(ProductCatalogCommandService.class);
            BuyerListingPublicationService publicationService =
                    context.getBean(BuyerListingPublicationService.class);
            BuyerCatalogQueryService queryService = context.getBean(BuyerCatalogQueryService.class);
            BuyerListingMapper listingMapper = context.getBean(BuyerListingMapper.class);
            BuyerListingVersionMapper versionMapper = context.getBean(BuyerListingVersionMapper.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", javax.sql.DataSource.class));

            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            buyerService.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                    "WUSHENG", "吾盛", "吾盛", null, null,
                    "AIFULI_MALL", "tenant-wusheng", now.minusMinutes(1), 1L));

            catalogService.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                    "CMCC", "中国移动", "China Mobile", null, 0, 1L));
            catalogService.changeBrandStatus("CMCC", CatalogEntityStatus.ACTIVE, 1L);
            catalogService.changeBrandStatus("CMCC", CatalogEntityStatus.INACTIVE, 1L);
            catalogService.changeBrandStatus("CMCC", CatalogEntityStatus.ACTIVE, 1L);

            catalogService.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                    "DIRECT_RECHARGE", "话费直充", null, 0, 1L));
            catalogService.changeCategoryStatus("DIRECT_RECHARGE", CatalogEntityStatus.ACTIVE, 1L);
            catalogService.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                    "TELCO_RECHARGE", "CMCC", "DIRECT_RECHARGE",
                    "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                    "话费直充", null, null, "充值后不可撤销", 1L));
            catalogService.changeSpuStatus("TELCO_RECHARGE", CatalogEntityStatus.ACTIVE, 1L);
            catalogService.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                    "TELCO_100", "TELCO_RECHARGE", "话费直充 100 元", "DIRECT_RECHARGE",
                    10_000L, "CNY", 1L));
            catalogService.changeSkuStatus("TELCO_100", CatalogEntityStatus.ACTIVE, 1L);

            String listingNo = catalogService.createBuyerListing(
                    new ProductCatalogCommandService.CreateBuyerListingCommand(
                            "WUSHENG", "TELCO_100", "WS-TELCO-100", 1L));
            assertThat(listingNo).startsWith("LST_").hasSize(30);

            BuyerListingPublicationService.PublishResult first = publicationService.publish(
                    publish(listingNo, 0, 9_800L, now, "首次发布"));
            BuyerListingDO afterFirst = listingMapper.selectByListingNo(listingNo);
            BuyerListingVersionDO firstVersion = versionMapper.selectById(afterFirst.getCurrentVersionId());
            assertThat(first.listingVersion()).isEqualTo(1);
            assertThat(firstVersion.getBuyerSettlementUnitPrice()).isEqualTo(9_800L);
            assertThat(afterFirst.getStatus()).isEqualTo(BuyerListingStatus.PUBLISHED.getCode());

            BuyerListingPublicationService.PublishResult second = publicationService.publish(
                    publish(listingNo, 1, 9_700L, now.plusMinutes(1), "调价发布"));
            BuyerListingDO afterSecond = listingMapper.selectByListingNo(listingNo);
            BuyerListingVersionDO secondVersion = versionMapper.selectById(afterSecond.getCurrentVersionId());
            assertThat(second.listingVersion()).isEqualTo(2);
            assertThat(secondVersion.getBuyerSettlementUnitPrice()).isEqualTo(9_700L);
            assertThat(versionMapper.selectById(firstVersion.getId()).getBuyerSettlementUnitPrice())
                    .isEqualTo(9_800L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_listing_version", Long.class))
                    .isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_buyer_catalog_change", Long.class))
                    .isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event", Long.class))
                    .isEqualTo(2L);

            BuyerCatalogPageReqDTO pageRequest = new BuyerCatalogPageReqDTO();
            pageRequest.setSourceSystem("AIFULI_MALL");
            pageRequest.setExternalTenantId("tenant-wusheng");
            PageResult<BuyerCatalogItemRespDTO> page = queryService.pageCatalog(pageRequest);
            assertThat(page.getTotal()).isEqualTo(1L);
            assertThat(page.getList().getFirst())
                    .extracting(BuyerCatalogItemRespDTO::getBuyerSkuCode,
                            BuyerCatalogItemRespDTO::getListingVersion,
                            BuyerCatalogItemRespDTO::getBuyerSettlementUnitPrice,
                            BuyerCatalogItemRespDTO::getCurrency,
                            BuyerCatalogItemRespDTO::getAvailabilityStatus)
                    .containsExactly("WS-TELCO-100", 2L, 9_700L, "CNY", "UNKNOWN");

            BuyerCatalogChangesReqDTO changesRequest = new BuyerCatalogChangesReqDTO();
            changesRequest.setSourceSystem("AIFULI_MALL");
            changesRequest.setExternalTenantId("tenant-wusheng");
            changesRequest.setCursor(0L);
            changesRequest.setLimit(100);
            BuyerCatalogChangeBatchRespDTO changes = queryService.getCatalogChanges(changesRequest);
            assertThat(changes.getChanges()).hasSize(2);
            assertThat(changes.getNextCursor()).isEqualTo(second.catalogCursor());
            assertThat(changes.getHasMore()).isFalse();

            assertThatThrownBy(() -> catalogService.createBuyerListing(
                    new ProductCatalogCommandService.CreateBuyerListingCommand(
                            "WUSHENG", "TELCO_100", "WS-TELCO-100-OTHER", 1L)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            publicationService.changeStatus(changeStatus(listingNo, 2, BuyerListingStatus.SUSPENDED));
            publicationService.changeStatus(changeStatus(listingNo, 3, BuyerListingStatus.PUBLISHED));
            buyerService.changeStatus("WUSHENG", BuyerStatus.SUSPENDED, 1L);
            buyerService.changeStatus("WUSHENG", BuyerStatus.ACTIVE, 1L);
            buyerService.changeStatus("WUSHENG", BuyerStatus.ARCHIVED, 1L);
            assertThatThrownBy(() -> buyerService.changeStatus("WUSHENG", BuyerStatus.ACTIVE, 1L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private BuyerListingPublicationService.PublishListingCommand publish(
            String listingNo, long expectedVersion, long price, LocalDateTime validFrom, String reason) {
        return new BuyerListingPublicationService.PublishListingCommand(listingNo, expectedVersion,
                price, "话费直充 100 元", validFrom, null, reason, 1L,
                "trace-master-data", "catalog-test", "test-command");
    }

    private BuyerListingPublicationService.ChangeListingStatusCommand changeStatus(
            String listingNo, long expectedVersion, BuyerListingStatus target) {
        return new BuyerListingPublicationService.ChangeListingStatusCommand(listingNo, expectedVersion,
                target, 1L, "trace-master-data", "catalog-test", "test-command");
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
            OutboxEventRepository.class, BuyerCommandService.class, BuyerResolver.class,
            ProductCatalogCommandService.class, ProductCenterTestConfiguration.class, BuyerListingPublicationService.class,
            BuyerCatalogQueryService.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
