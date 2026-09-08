package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierInvoiceService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierInvoiceService.SaveInvoiceCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierInvoiceService.SupplierInvoiceView;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.CreateSupplierProfileCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.UpdateSupplierProfileCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService.SupplierDetail;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierInvoiceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierInvoiceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商开票：无行预填不落库，保存明文且不回写档案。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md}。
 */
class SupplierAdminInvoiceIntegrationTest {

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
    void invoicePrefillsWithoutInsertThenPersistsIndependentlyOfProfile() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            SupplierProfileCommandService command = context.getBean(SupplierProfileCommandService.class);
            SupplierProfileQueryService query = context.getBean(SupplierProfileQueryService.class);
            SupplierInvoiceService invoices = context.getBean(SupplierInvoiceService.class);
            SupplierInvoiceMapper invoiceMapper = context.getBean(SupplierInvoiceMapper.class);
            SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);

            String supplierCode = command.create(createCommand("开票供应商", "91110000000000011X"));
            Long supplierId = supplierMapper.selectByCode(supplierCode).getId();

            SupplierInvoiceView prefilled = invoices.get(supplierCode);
            assertThat(prefilled.persisted()).isFalse();
            assertThat(prefilled.id()).isNull();
            assertThat(prefilled.titleType()).isEqualTo("ENTERPRISE");
            assertThat(prefilled.invoiceTitle()).isEqualTo("测试科技有限公司");
            assertThat(prefilled.socialCreditCode()).isEqualTo("91110000000000011X");
            assertThat(prefilled.phone()).isEqualTo("13800138000");
            assertThat(prefilled.bankName()).isEqualTo("测试银行");
            assertThat(prefilled.bankAccount()).isEqualTo("6222021234567890");
            assertThat(prefilled.invoiceAddress()).isEqualTo("上海市浦东新区");
            assertThat(invoiceMapper.selectCount(new LambdaQueryWrapper<SupplierInvoiceDO>()
                    .eq(SupplierInvoiceDO::getSupplierId, supplierId))).isZero();

            SupplierInvoiceView saved = invoices.save(supplierCode, new SaveInvoiceCommand(
                    "ENTERPRISE", "独立发票抬头", "91110000MA1K3ABC0X", "13900000000",
                    "招商银行", "6225880000000001", "浦东开票地址"), 8L);
            assertThat(saved.persisted()).isTrue();
            assertThat(saved.id()).isNotNull();
            assertThat(saved.invoiceTitle()).isEqualTo("独立发票抬头");
            assertThat(saved.bankAccount()).isEqualTo("6225880000000001");
            SupplierInvoiceDO row = invoiceMapper.selectBySupplierId(supplierId);
            assertThat(row.getBankAccount()).isEqualTo("6225880000000001");
            assertThat(row.getBankName()).isEqualTo("招商银行");

            command.update(updateCommand(supplierCode, "档案改名供应商", "91110000000000011X",
                    "档案新公司", "档案新银行"));
            SupplierDetail afterProfile = query.get(supplierCode);
            assertThat(afterProfile.supplierName()).isEqualTo("档案改名供应商");
            assertThat(afterProfile.legalEntityName()).isEqualTo("档案新公司");
            assertThat(afterProfile.bankName()).isEqualTo("档案新银行");
            assertThat(afterProfile.bankAccount()).isEqualTo("6222021234567890");

            SupplierInvoiceView afterProfileChange = invoices.get(supplierCode);
            assertThat(afterProfileChange.persisted()).isTrue();
            assertThat(afterProfileChange.invoiceTitle()).isEqualTo("独立发票抬头");
            assertThat(afterProfileChange.bankName()).isEqualTo("招商银行");
            assertThat(afterProfileChange.bankAccount()).isEqualTo("6225880000000001");
        }
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
                "supplier-invoice-it");
    }

    private static UpdateSupplierProfileCommand updateCommand(String supplierCode, String name,
                                                              String creditCode, String legalEntity,
                                                              String bankName) {
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
                legalEntity,
                creditCode,
                "李四",
                "0005",
                "13900139000",
                "上海市浦东新区",
                bankName,
                "6222021234567890",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
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
            AesGcmEnvelopeCrypto.class, SupplierProfileCommandService.class,
            SupplierProfileQueryService.class, SupplierInvoiceService.class})
    static class TestConfiguration {
    }
}
