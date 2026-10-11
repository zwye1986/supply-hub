package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.DraftLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.OfferListItem;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.CreateSupplierProfileCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.UpdateSupplierProfileCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService.SupplierDetail;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService.ScopeLine;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSettlementAccountDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSettlementLedgerDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSettlementAccountMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSettlementLedgerMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.testcontainers.containers.MySQLContainer;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_CREDIT_CODE_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_OPENING_ALREADY_DONE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_SCOPE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 供应商管理端建档、期初、供货配置与合作商品上架。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#15-供应商管理端原型已确认}。</p>
 */
class SupplierAdminProfileIntegrationTest {

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
    void createsOpeningBalanceZeroAndRejectsSecondBalanceAndCountsDistinctSpu() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            SupplierProfileCommandService command = context.getBean(SupplierProfileCommandService.class);
            SupplierProfileQueryService query = context.getBean(SupplierProfileQueryService.class);
            SupplierSupplyScopeService scopes = context.getBean(SupplierSupplyScopeService.class);
            SupplierCooperativeProductService offers = context.getBean(SupplierCooperativeProductService.class);
            ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
            SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);
            SupplierSettlementAccountMapper accountMapper =
                    context.getBean(SupplierSettlementAccountMapper.class);
            SupplierSettlementLedgerMapper ledgerMapper =
                    context.getBean(SupplierSettlementLedgerMapper.class);

            String supplierCode = command.create(createCommand("测试供应商", "91110000000000001X", 0L));
            assertThat(supplierCode).matches("SPL_[0-9]{3}");

            SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
            assertThat(supplier.getSupplierType()).isEqualTo("STANDARD_SUPPLIER");
            assertThat(supplier.getStatus()).isEqualTo(SupplierStatus.ACTIVE.getCode());

            SupplierSettlementAccountDO account = accountMapper.selectBySupplierId(supplier.getId());
            assertThat(account.getBalance()).isZero();
            assertThat(account.getAccountNo()).startsWith("SSA_");
            List<SupplierSettlementLedgerDO> ledgers = ledgerMapper.selectList(
                    new LambdaQueryWrapper<SupplierSettlementLedgerDO>()
                            .eq(SupplierSettlementLedgerDO::getAccountId, account.getId()));
            assertThat(ledgers).hasSize(1);
            assertThat(ledgers.getFirst().getEntryKey())
                    .isEqualTo("OPENING_BALANCE:" + supplier.getId() + ":CNY");
            assertThat(ledgers.getFirst().getBalanceDelta()).isZero();
            assertThat(ledgers.getFirst().getAuditNote()).isEqualTo("供应商建档期初");

            SupplierDetail detail = query.get(supplierCode);
            assertThat(detail.settlementBalanceCents()).isZero();
            assertThat(detail.accountBalanceCents()).isZero();
            assertThat(detail.deliveryRegions()).isEmpty();
            assertThat(detail.bankAccount()).isEqualTo("6222021234567890");
            assertThat(detail.legalPersonMobile()).isEqualTo("13900139000");
            assertThat(detail.id()).isEqualTo(supplier.getId());
            // createCommand 通过单列 licenseFileKey 回填，详情数组应含同一 fileKey
            assertThat(detail.licenseFileKeys()).containsExactly("license-key-1");
            assertThat(detail.licenseFileKey()).isEqualTo("license-key-1");

            String licensedCode = command.create(createCommand("证件供应商", "91110000000000002X", 0L,
                    List.of("license-key-a", "license-key-b"), null));
            SupplierDetail licensed = query.get(licensedCode);
            assertThat(licensed.id()).isNotNull();
            assertThat(licensed.licenseFileKeys()).containsExactly("license-key-a", "license-key-b");
            assertThat(licensed.qualificationFileKeys()).isEmpty();

            assertThatThrownBy(() -> command.update(updateCommand(supplierCode, "测试供应商",
                    "91110000000000001X", 0L)))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(SUPPLIER_OPENING_ALREADY_DONE.getCode());

            command.update(updateCommand(supplierCode, "测试供应商", "91110000000000001X", null));
            SupplierDetail afterUpdate = query.get(supplierCode);
            assertThat(afterUpdate.licenseFileKey()).isEqualTo("license-key-1");
            assertThat(afterUpdate.licenseFileKeys()).containsExactly("license-key-1");
            assertThat(afterUpdate.idFrontFileKey()).isEqualTo("id-front-key");
            assertThat(afterUpdate.legalPersonIdNo()).isEqualTo("0005");
            assertThat(afterUpdate.onboardedAt().getYear()).isGreaterThan(2020);

            assertThatThrownBy(() -> command.create(createCommand("测试供应商", "91110000000000001X", 0L)))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(SUPPLIER_CREDIT_CODE_DUPLICATE.getCode());

            command.changeStatus(supplierCode, SupplierStatus.BLACKLIST, 1L);
            command.changeStatus(supplierCode, SupplierStatus.ACTIVE, 1L);

            seedCatalog(catalog);
            scopes.replaceAll(supplierCode, List.of(new ScopeLine(
                    "MOVIE", "MOVIE", "MOVIE_TICKET", List.of("API_DIRECT", "H5"), 100L, 101L)), 1L);
            var savedScopes = query.listSupplyScopes(supplierCode);
            assertThat(savedScopes).hasSize(1);
            assertThat(savedScopes.getFirst().supplyModes()).containsExactly("API_DIRECT", "H5");
            assertThat(savedScopes.getFirst().minQuantity()).isEqualTo(101L);

            assertThatThrownBy(() -> scopes.replaceAll(supplierCode, List.of(
                    new ScopeLine("MOVIE", "MOVIE", "MOVIE_TICKET", List.of("API_DIRECT"), 100L, 1L),
                    new ScopeLine("MOVIE", "MOVIE", "MOVIE_TICKET", List.of("H5"), 50L, 1L)), 1L))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(SUPPLIER_SCOPE_CONFLICT.getCode());

            List<String> offerNos = offers.createDrafts(supplierCode, List.of(
                    new DraftLine("SKU_001_100", "FIXED", 9_800L, null, null, "API_DIRECT", 100L, 1L, "QNM-SKU-001-100"),
                    new DraftLine("SKU_001_200", "FIXED", 19_800L, null, null, "API_DIRECT", 50L, 1L, "QNM-SKU-001-200")), 1L);
            assertThat(offerNos).hasSize(2);
            SupplierDetail afterOffers = query.get(supplierCode);
            assertThat(afterOffers.spuCount()).isEqualTo(1L);

            assertThatThrownBy(() -> offers.createDrafts(supplierCode, List.of(
                    new DraftLine("SKU_001_100", "FIXED", 9_800L, null, null, "API_DIRECT", 100L, 1L, "QNM-SKU-001-100")), 1L))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode())
                    .isEqualTo(SUPPLIER_SCOPE_CONFLICT.getCode());

            offers.retire(supplierCode, offerNos.getFirst(), 1L);
            var remainingPage = offers.page(supplierCode, null, null, null, 1, 20);
            assertThat(remainingPage.getList()).extracting(OfferListItem::offerNo)
                    .doesNotContain(offerNos.getFirst());

            List<String> readded = offers.createDrafts(supplierCode, List.of(
                    // 重新选品建立新的下架身份；未退役身份的外部编码唯一，旧退役身份与冻结版本继续保留。
                    // 设计见docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#1-核心业务规则。
                    new DraftLine("SKU_001_100", "FIXED", 9_500L, null, null, "API_DIRECT", 80L, 1L, "QNM-SKU-001-100-RE")), 1L);
            assertThat(readded).hasSize(1);
            List<SupplierOfferDO> live = offers.listLive(supplierCode);
            assertThat(live).hasSize(2);
            assertThat(live).extracting(SupplierOfferDO::getStatus)
                    .containsOnly(OfferStatus.SUSPENDED.getCode());

            String patchOfferNo = offerNos.get(1);
            offers.changeOfferStatus(supplierCode, patchOfferNo, OfferStatus.SUSPENDED, 1L);
            assertThat(offers.listLive(supplierCode)).filteredOn(row -> patchOfferNo.equals(row.getOfferNo()))
                    .extracting(SupplierOfferDO::getStatus)
                    .containsExactly(OfferStatus.SUSPENDED.getCode());
            offers.changeOfferStatus(supplierCode, patchOfferNo, OfferStatus.PUBLISHED, 1L);
            // 本上下文的发布编排为mock：验证旧PATCH必须委派，不能直接将状态改为上架。
            // 真实冻结/绑定/状态切换与失败回滚由SupplierOfferLifecycleIntegrationTest覆盖。
            org.mockito.Mockito.verify(context.getBean(SupplierOfferPublicationService.class)).listOnShelf(
                    org.mockito.ArgumentMatchers.argThat(publishCommand -> supplierCode.equals(publishCommand.supplierCode())
                            && patchOfferNo.equals(publishCommand.offerNo()) && Long.valueOf(1L).equals(publishCommand.operatorId())));
            assertThat(offers.listLive(supplierCode)).filteredOn(row -> patchOfferNo.equals(row.getOfferNo()))
                    .extracting(SupplierOfferDO::getStatus)
                    .containsExactly(OfferStatus.SUSPENDED.getCode());
        }
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
                10_000L, "CNY", 1L));
        catalog.changeSkuStatus("SKU_001_100", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "SKU_001_200", "SPU_001", "电影票 200", FulfillmentType.DIRECT_RECHARGE.name(),
                20_000L, "CNY", 1L));
        catalog.changeSkuStatus("SKU_001_200", CatalogEntityStatus.ACTIVE, 1L);
    }

    private static CreateSupplierProfileCommand createCommand(String name, String creditCode, long opening) {
        return createCommand(name, creditCode, opening, null, null);
    }

    private static CreateSupplierProfileCommand createCommand(String name, String creditCode, long opening,
                                                              List<String> licenseFileKeys,
                                                              List<String> qualificationFileKeys) {
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
                licenseFileKeys,
                qualificationFileKeys,
                opening,
                1L,
                "supplier-admin-it");
    }

    private static UpdateSupplierProfileCommand updateCommand(String supplierCode, String name,
                                                              String creditCode, Long opening) {
        return new UpdateSupplierProfileCommand(
                supplierCode,
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
                opening,
                1L);
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
            SupplierProfileCommandService.class, SupplierProfileQueryService.class,
            SupplierSupplyScopeService.class, SupplierCooperativeProductService.class})
    static class TestConfiguration {
        /** 档案/详情链路不涉及发布；只补合作商品服务新增的发布编排依赖。 */
        @Bean SupplierOfferPublicationService publicationService() {
            return org.mockito.Mockito.mock(SupplierOfferPublicationService.class);
        }
    }
}
