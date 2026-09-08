package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.security.EnvironmentCredentialProvider;
import com.aifuli.module.supplychain.infrastructure.security.SupplierEndpointPolicy;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorValidationRecordService;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorValidationService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorPublicationIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final String TEST_SECRET = "integration-secret-must-not-enter-database";

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
    void publishesClosedQnmConnectorAndImmutableOfferVersionsWithoutPersistingSecrets() {
        QnmGameOrderV1Metadata qnmMetadata = new QnmGameOrderV1Metadata();
        assertThatThrownBy(() -> new AdapterMetadataRegistry(List.of(qnmMetadata, qnmMetadata)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate adapter metadata");

        try (ConfigurableApplicationContext context = applicationContext()) {
            ProductCatalogCommandService catalog = context.getBean(ProductCatalogCommandService.class);
            SupplierConfigurationCommandService configuration =
                    context.getBean(SupplierConfigurationCommandService.class);
            ConnectorValidationService validation = context.getBean(ConnectorValidationService.class);
            ConnectorPublicationService publication = context.getBean(ConnectorPublicationService.class);
            SupplierOfferPublicationService offerService = context.getBean(SupplierOfferPublicationService.class);
            PublishedConnectorConfigurationResolver runtimeResolver =
                    context.getBean(PublishedConnectorConfigurationResolver.class);
            SupplierConnectorMapper connectorMapper = context.getBean(SupplierConnectorMapper.class);
            SupplierConnectorVersionMapper connectorVersionMapper =
                    context.getBean(SupplierConnectorVersionMapper.class);
            SupplierOfferMapper offerMapper = context.getBean(SupplierOfferMapper.class);
            SupplierOfferVersionMapper offerVersionMapper = context.getBean(SupplierOfferVersionMapper.class);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(
                    "supplyChainDataSource", javax.sql.DataSource.class));

            createDirectRechargeSku(catalog);
            configuration.createSupplier(new SupplierConfigurationCommandService.CreateSupplierCommand(
                    "QINAMENG", "奇那梦", "奇那梦", "STANDARD_SUPPLIER", null, 1L));
            String slotNo = configuration.createCredentialSlot(
                    new SupplierConfigurationCommandService.CreateCredentialSlotCommand(
                            "ENV", "env:QNM_TEST_USERKEY", "qnm-****", "v1", 1L));

            assertThatThrownBy(() -> configuration.createConnectorDraft(draft(
                    slotNo, "UNKNOWN_ADAPTER", "https://api.qinameng.com", "{}", "UNKNOWN")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> configuration.createConnectorDraft(draft(
                    slotNo, QnmGameOrderV1Metadata.TYPE, "https://api.qinameng.com",
                    "{\"appKey\":\"forbidden\"}", "BAD_CONFIG")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown field: appKey");

            SupplierConfigurationCommandService.DraftConnectorResult unsafe =
                    configuration.createConnectorDraft(draft(
                            slotNo, QnmGameOrderV1Metadata.TYPE, "https://127.0.0.1", "{}", "UNSAFE"));
            configuration.initializeQnmStatusMappings(unsafe.connectorVersionId(), 1L);
            assertThatThrownBy(() -> validation.validate(validate(unsafe.connectorVersionId())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("local or private");

            SupplierConfigurationCommandService.DraftConnectorResult draft =
                    configuration.createConnectorDraft(draft(
                            slotNo, QnmGameOrderV1Metadata.TYPE, "https://api.qinameng.com", "{}", "PRIMARY"));
            assertThat(configuration.initializeQnmStatusMappings(draft.connectorVersionId(), 1L)).isEqualTo(7);
            configuration.addErrorCodeMapping(
                    new SupplierConfigurationCommandService.AddErrorCodeMappingCommand(
                            draft.connectorVersionId(), "TIMEOUT", "", "RESULT_UNKNOWN",
                            "QUERY_FIRST", false, 1L));
            assertThatThrownBy(() -> configuration.addErrorCodeMapping(
                    new SupplierConfigurationCommandService.AddErrorCodeMappingCommand(
                            draft.connectorVersionId(), "X", "", "RESULT_UNKNOWN",
                            "DROP_TABLE", false, 1L))).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> runtimeResolver.resolve(draft.connectorVersionId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not published");
            ConnectorValidationService.ValidationResult validationResult =
                    validation.validate(validate(draft.connectorVersionId()));
            assertThat(validationResult.validationExpiresAt()).isAfter(validationResult.validatedAt());
            ConnectorPublicationService.PublishResult connectorPublished = publication.publish(
                    new ConnectorPublicationService.PublishConnectorCommand(
                            draft.connectorNo(), draft.connectorVersionId(), 0L, 1L,
                            "一期奇那梦正式发布", "trace-connector", null, null));

            PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration runtime =
                    runtimeResolver.resolve(draft.connectorVersionId());
            assertThat(runtime.adapterType()).isEqualTo(QnmGameOrderV1Metadata.TYPE);
            assertThat(runtime.capabilities()).containsExactlyInAnyOrderElementsOf(qnmMetadata.capabilities());
            SupplierConnectorDO connector = connectorMapper.selectByNo(draft.connectorNo());
            SupplierConnectorVersionDO connectorVersion = connectorVersionMapper.selectById(draft.connectorVersionId());
            assertThat(connector.getCurrentVersionId()).isEqualTo(draft.connectorVersionId());
            assertThat(connector.getVersion()).isEqualTo(1L);
            assertThat(connectorVersion.getStatus()).isEqualTo(ConnectorVersionStatus.PUBLISHED.getCode());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_connector_capability "
                    + "WHERE connector_version_id = ? AND enabled = 1", Long.class, draft.connectorVersionId()))
                    .isEqualTo(5L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_status_mapping_definition "
                    + "WHERE connector_version_id = ? AND status = 10", Long.class, draft.connectorVersionId()))
                    .isEqualTo(7L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_error_code_map "
                    + "WHERE connector_version_id = ? AND status = 10", Long.class, draft.connectorVersionId()))
                    .isEqualTo(1L);

            String sourceNo = offerService.createRemoteOnDemandInventory(
                    new SupplierOfferPublicationService.CreateInventorySourceCommand(
                            "QINAMENG", "QNM_TELCO_100", "QNM-REMOTE", 1L));
            offerService.activateInventorySource(sourceNo, 0L, 1L);
            String offerNo = offerService.createOffer(new SupplierOfferPublicationService.CreateOfferCommand(
                    "QINAMENG", "QNM_TELCO_100", "QNM-TELCO-100", 1L));
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            SupplierOfferPublicationService.PublishResult first = offerService.publish(
                    publishOffer(offerNo, 0L, draft.connectorVersionId(), sourceNo, 9_700L, now));
            SupplierOfferDO afterFirst = offerMapper.selectByNo(offerNo);
            SupplierOfferVersionDO firstVersion = offerVersionMapper.selectById(afterFirst.getCurrentVersionId());
            SupplierOfferPublicationService.PublishResult second = offerService.publish(
                    publishOffer(offerNo, 1L, draft.connectorVersionId(), sourceNo, 9_600L, now.plusMinutes(1)));
            SupplierOfferDO afterSecond = offerMapper.selectByNo(offerNo);
            SupplierOfferVersionDO secondVersion = offerVersionMapper.selectById(afterSecond.getCurrentVersionId());
            assertThat(first.offerVersionNo()).isEqualTo(1);
            assertThat(second.offerVersionNo()).isEqualTo(2);
            assertThat(afterSecond.getStatus()).isEqualTo(OfferStatus.PUBLISHED.getCode());
            assertThat(firstVersion.getSupplierSettlementUnitPrice()).isEqualTo(9_700L);
            assertThat(secondVersion.getSupplierSettlementUnitPrice()).isEqualTo(9_600L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_external_sku_binding "
                    + "WHERE offer_id = ? AND offer_version_id = ?", Long.class,
                    afterSecond.getId(), secondVersion.getId())).isEqualTo(1L);
            assertThatThrownBy(() -> offerService.publish(invalidReplayOffer(
                    offerNo, 2L, draft.connectorVersionId(), sourceNo, now.plusMinutes(2))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REPLAY_SAME_KEY");

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_credential_slot "
                    + "WHERE secret_ref LIKE ?", Long.class, "%" + TEST_SECRET + "%")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_connector_version "
                    + "WHERE CAST(adapter_config_json AS CHAR) LIKE ?", Long.class,
                    "%" + TEST_SECRET + "%")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event "
                    + "WHERE CAST(payload_json AS CHAR) LIKE ?", Long.class,
                    "%" + TEST_SECRET + "%")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_config_publish_record",
                    Long.class)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event", Long.class)).isEqualTo(3L);

            publication.retire(new ConnectorPublicationService.RetireConnectorVersionCommand(
                    draft.connectorNo(), connectorPublished.connectorVersionId(), 1L,
                    "协议版本退役验证", "trace-retire", null, null));
            assertThatThrownBy(() -> runtimeResolver.resolve(draft.connectorVersionId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not published");
            assertThat(connectorMapper.selectByNo(draft.connectorNo()).getCurrentVersionId()).isNull();
            assertThat(connectorVersionMapper.selectById(draft.connectorVersionId()).getStatus())
                    .isEqualTo(ConnectorVersionStatus.RETIRED.getCode());
        }
    }

    private static void createDirectRechargeSku(ProductCatalogCommandService catalog) {
        catalog.createBrand(new ProductCatalogCommandService.CreateBrandCommand(
                "QNM_BRAND", "奇那梦测试品牌", null, null, 0, 1L));
        catalog.changeBrandStatus("QNM_BRAND", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                "QNM_DIRECT", "奇那梦直充", null, 0, 1L));
        catalog.changeCategoryStatus("QNM_DIRECT", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                "QNM_TELCO", "QNM_BRAND", "QNM_DIRECT",
                "EQUITY", "EQUITY_PRODUCT", "VIRTUAL_CARD",
                "奇那梦话费直充", null, null, "中台受理后不可自动退款或撤销", 1L));
        catalog.changeSpuStatus("QNM_TELCO", CatalogEntityStatus.ACTIVE, 1L);
        catalog.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                "QNM_TELCO_100", "QNM_TELCO", "话费直充 100 元", "DIRECT_RECHARGE",
                10_000L, "CNY", "DIRECT_RECHARGE_TARGET", 1,
                "DIRECT_RECHARGE_RESULT", 1, 1L));
        catalog.changeSkuStatus("QNM_TELCO_100", CatalogEntityStatus.ACTIVE, 1L);
    }

    private static SupplierConfigurationCommandService.CreateConnectorDraftCommand draft(
            String slotNo, String adapterType, String baseUrl, String config, String code) {
        return new SupplierConfigurationCommandService.CreateConnectorDraftCommand(
                "QINAMENG", code, adapterType, baseUrl, "qnm-user", slotNo, "V1",
                "/callbacks/qnm/game-order/v1", 3_000, 10_000, 100, 20, 1, config, 1L);
    }

    private static ConnectorValidationService.ValidateConnectorCommand validate(Long versionId) {
        return new ConnectorValidationService.ValidateConnectorCommand(versionId,
                Set.of("ISSUE_ASSET", "CREATE_RECHARGE", "QUERY_ORDER",
                        "RECEIVE_CALLBACK", "QUERY_BALANCE"),
                Duration.ofMinutes(10), 1L, "验证奇那梦协议与凭证引用", "trace-validation");
    }

    private static SupplierOfferPublicationService.PublishOfferCommand publishOffer(
            String offerNo, long expectedVersion, Long connectorVersionId,
            String sourceNo, long price, LocalDateTime validFrom) {
        return new SupplierOfferPublicationService.PublishOfferCommand(
                offerNo, expectedVersion, "QNM-TELCO-100", price, connectorVersionId, sourceNo,
                1L, 1L, 1L, Capability.CREATE_RECHARGE.name(), "QUERY_OR_CALLBACK",
                "DEDUP_ONLY", "QUERY_ORDER", "FORBIDDEN", "NONE", 1, "{}",
                validFrom, null, "奇那梦话费直充报价", 1L,
                "trace-offer", null, null);
    }

    private static SupplierOfferPublicationService.PublishOfferCommand invalidReplayOffer(
            String offerNo, long expectedVersion, Long connectorVersionId,
            String sourceNo, LocalDateTime validFrom) {
        return new SupplierOfferPublicationService.PublishOfferCommand(
                offerNo, expectedVersion, "QNM-TELCO-100", 9_500L, connectorVersionId, sourceNo,
                1L, 1L, 1L, Capability.CREATE_RECHARGE.name(), "QUERY",
                "DEDUP_ONLY", "REPLAY_SAME_KEY", "FORBIDDEN", "NONE", 1, "{}",
                validFrom, null, "非法重放策略", 1L, "trace-invalid", null, null);
    }

    private ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.connector.allowed-hosts", "api.qinameng.com");
        properties.put("QNM_TEST_USERKEY", TEST_SECRET);
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
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class,
            OutboxEventRepository.class, ProductCatalogCommandService.class,
            QnmGameOrderV1Metadata.class, AdapterMetadataRegistry.class,
            ClosedJsonSchemaValidator.class,
            EnvironmentCredentialProvider.class, SupplierEndpointPolicy.class,
            SupplierConfigurationCommandService.class, ConnectorValidationRecordService.class,
            ConnectorValidationService.class, ConnectorPublicationService.class,
            SupplierOfferPublicationService.class, PublishedConnectorConfigurationResolver.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
