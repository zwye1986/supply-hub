package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.infrastructure.security.DefaultCredentialSetResolver;
import com.aifuli.module.supplychain.infrastructure.security.EnvCredentialItemResolver;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCredentialItemResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationQueryService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCredentialCommandService;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialItemMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.testsupport.ProductCenterTestConfiguration;
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

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 凭据写端点（F1-hybrid，§6.3）的安全契约集成测试：ENVELOPE 明文经真实 DB 落库后仍是信封密文，
 * 可被 {@link DefaultCredentialSetResolver} 以身份绑定 AAD 原样解回，且 T4 只读投影绝不泄露密文。
 *
 * <p>锁定不变量 11：录入只写不回显、读取只返回 masked_hint、AAD 绑定凭据项身份防密文搬移。设计见
 * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#63-凭据写f1-hybrid。</p>
 */
class SupplierCredentialWriteIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final String KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String PLAINTEXT = "IT-envelope-secret-值-9527";

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
    void envelopeItemIsStoredAsCiphertextDecryptableAndNeverLeaksThroughProjection() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            SupplierConfigurationCommandService configuration =
                    context.getBean(SupplierConfigurationCommandService.class);
            SupplierCredentialCommandService credential =
                    context.getBean(SupplierCredentialCommandService.class);
            SupplierConfigurationQueryService query = context.getBean(SupplierConfigurationQueryService.class);
            DefaultCredentialSetResolver resolver = context.getBean(DefaultCredentialSetResolver.class);
            CredentialSetMapper setMapper = context.getBean(CredentialSetMapper.class);
            CredentialItemMapper itemMapper = context.getBean(CredentialItemMapper.class);
            SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            String supplierCode = configuration.createSupplier(
                    new SupplierConfigurationCommandService.CreateSupplierCommand(
                            null, "QNM", "QNM", "STANDARD_SUPPLIER", null, 1L));
            Long setId = credential.createCredentialSet(
                    new SupplierCredentialCommandService.CreateCredentialSetCommand(
                            supplierCode, "QNM_IT_SET", 1L));
            credential.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(
                    supplierCode, "QNM_IT_SET", "secretKey", "ENVELOPE", null, PLAINTEXT,
                    "qnm-****", 1, 1L));

            // 落库为信封密文引用，绝不含明文。
            CredentialItemDO stored = itemMapper.selectBySetAndKey(setId, "secretKey");
            assertThat(stored.getProviderType()).isEqualTo("ENVELOPE");
            assertThat(stored.getSecretRef()).startsWith("envelope:");
            assertThat(stored.getSecretRef()).doesNotContain(PLAINTEXT);

            // 身份绑定 AAD 可原样解回明文（ proves 加密正确 + AAD=setId|itemKey）。
            Map<String, char[]> resolved = resolver.resolveSet(setId);
            assertThat(new String(resolved.get("secretKey"))).isEqualTo(PLAINTEXT);

            // T4 只读投影序列化后绝不出现明文或密文引用。
            List<SupplierConfigurationQueryService.CredentialSetView> projection =
                    query.listCredentialSets(supplierCode);
            String serialized = objectMapper.writeValueAsString(projection);
            assertThat(serialized).doesNotContain(PLAINTEXT);
            assertThat(serialized).doesNotContain("envelope:");
            assertThat(projection).singleElement().satisfies(view -> {
                assertThat(view.setCode()).isEqualTo("QNM_IT_SET");
                assertThat(view.items()).singleElement().satisfies(item -> {
                    assertThat(item.itemKey()).isEqualTo("secretKey");
                    assertThat(item.maskedHint()).isEqualTo("qnm-****");
                });
            });
            assertThat(setMapper.selectById(setId)).isNotNull();
            assertThat(supplierMapper.selectByCode(supplierCode)).isNotNull();
        }
    }

    private ConfigurableApplicationContext applicationContext() {
        java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.security.key-version", "test-v1");
        properties.put("aifuli.supply-chain.security.envelope-key-base64", KEY_BASE64);
        properties.put("aifuli.supply-chain.security.fingerprint-key-base64", KEY_BASE64);
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE).properties(properties).run();
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
    @Import({com.aifuli.module.supplychain.tradecore.supply.application.ConnectorCredentialBindingValidator.class,SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            ProductCatalogCommandService.class, ProductCenterTestConfiguration.class,
            QnmGameOrderV1Metadata.class, AdapterMetadataRegistry.class, ClosedJsonSchemaValidator.class,
            SupplierConfigurationCommandService.class, SupplierConfigurationQueryService.class,
            AesGcmEnvelopeCrypto.class, SupplierCredentialCommandService.class,
            EnvelopeCredentialItemResolver.class, EnvCredentialItemResolver.class,
            DefaultCredentialSetResolver.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
