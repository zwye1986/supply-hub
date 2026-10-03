package com.aifuli.module.supplychain.tradecore.supply;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.security.EnvironmentCredentialProvider;
import com.aifuli.module.supplychain.infrastructure.security.SupplierEndpointPolicy;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorValidationRecordService;
import com.aifuli.module.supplychain.tradecore.supply.application.ConnectorValidationService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierConfigurationQueryService;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSetDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialItemMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
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
import org.testcontainers.containers.MySQLContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 技术菜单只读 GET 面（T1–T5）的投影契约与凭据脱敏不变量集成测试。
 *
 * <p>重点验证 T4：凭据集/项列表绝不返回 {@code secret_ref} 值或密文，只返回
 * {@code item_key/provider_type/masked_hint/status}。设计见
 * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#61-技术菜单只读-get-面supplierconfigurationadmincontroller当前零-get。</p>
 */
class SupplierConfigurationQueryIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain").withUsername("supply_chain").withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static final String SECRET_MARKER = "QNM_QUERY_TEST_SECRET_MARKER";

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
    void projectsAdapterTypesConnectorsVersionCredentialSetsAndMappingsWithoutLeakingSecrets() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            SupplierConfigurationCommandService configuration =
                    context.getBean(SupplierConfigurationCommandService.class);
            SupplierConfigurationQueryService query = context.getBean(SupplierConfigurationQueryService.class);
            ConnectorValidationService validation = context.getBean(ConnectorValidationService.class);
            com.aifuli.module.supplychain.tradecore.supply.application.ConnectorPublicationService publication =
                    context.getBean(
                            com.aifuli.module.supplychain.tradecore.supply.application.ConnectorPublicationService.class);
            CredentialSetMapper credentialSetMapper = context.getBean(CredentialSetMapper.class);
            CredentialItemMapper credentialItemMapper = context.getBean(CredentialItemMapper.class);
            SupplierMapper supplierMapper = context.getBean(SupplierMapper.class);
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            String supplierCode = configuration.createSupplier(
                    new SupplierConfigurationCommandService.CreateSupplierCommand(
                            null, "QNM", "QNM", "STANDARD_SUPPLIER", null, 1L));
            Long supplierId = supplierMapper.selectByCode(supplierCode).getId();
            String slotNo = configuration.createCredentialSlot(
                    new SupplierConfigurationCommandService.CreateCredentialSlotCommand(
                            "ENV", "env:QNM_QUERY_TEST_USERKEY", "qnm-****", "v1", 1L));

            // T1：适配器类型目录来自代码注册表，暴露能力/凭据键/模板计数（封闭集，前端据此渲染）。
            List<SupplierConfigurationQueryService.AdapterTypeView> adapterTypes = query.listAdapterTypes();
            assertThat(adapterTypes).extracting(
                    SupplierConfigurationQueryService.AdapterTypeView::adapterType)
                    .contains(QnmGameOrderV1Metadata.TYPE);
            SupplierConfigurationQueryService.AdapterTypeView qnmView = adapterTypes.stream()
                    .filter(view -> QnmGameOrderV1Metadata.TYPE.equals(view.adapterType()))
                    .findFirst().orElseThrow();
            assertThat(qnmView.capabilities()).containsExactlyInAnyOrderElementsOf(
                    new QnmGameOrderV1Metadata().capabilities().stream()
                            .map(Enum::name).toList());
            assertThat(qnmView.credentialKeys()).containsExactly("secretKey");
            assertThat(qnmView.statusMappingTemplateCount()).isEqualTo(7);
            assertThat(qnmView.errorCodeMappingTemplateCount()).isZero();

            // 建连接器草稿 → 初始化映射 → 校验（写能力）→ 发布，供 T2/T3/T5 投影。
            SupplierConfigurationCommandService.DraftConnectorResult draft =
                    configuration.createConnectorDraft(
                            new SupplierConfigurationCommandService.CreateConnectorDraftCommand(
                                    supplierCode, "QNM_PRIMARY", QnmGameOrderV1Metadata.TYPE,
                                    "https://api.qinameng.com", "qnm-user", slotNo, "V1",
                                    "/callbacks/qnm/game-order/v1", 3_000, 10_000, 100, 20, "{}", 1L));
            assertThat(configuration.initializeMappings(draft.connectorVersionId(), 1L)).isEqualTo(7);
            configuration.addErrorCodeMapping(
                    new SupplierConfigurationCommandService.AddErrorCodeMappingCommand(
                            draft.connectorVersionId(), "TIMEOUT", "", "RESULT_UNKNOWN",
                            "QUERY_FIRST", false, 1L));
            validation.validate(new ConnectorValidationService.ValidateConnectorCommand(
                    draft.connectorVersionId(),
                    Set.of("ISSUE_ASSET", "CREATE_RECHARGE", "QUERY_ORDER", "RECEIVE_CALLBACK", "QUERY_BALANCE"),
                    Duration.ofMinutes(10), 1L, "只读面校验", "trace-query-validation"));
            publication.publish(new com.aifuli.module.supplychain.tradecore.supply.application
                    .ConnectorPublicationService.PublishConnectorCommand(
                    draft.connectorNo(), draft.connectorVersionId(), 0L, 1L,
                    "只读面发布", "trace-query-publish", null, null));

            // T2：供应商连接器列表投影（含当前已发布版本 id）。
            List<SupplierConfigurationQueryService.ConnectorListItem> connectors =
                    query.listConnectors(supplierCode);
            assertThat(connectors).singleElement().satisfies(item -> {
                assertThat(item.connectorNo()).isEqualTo(draft.connectorNo());
                assertThat(item.adapterType()).isEqualTo(QnmGameOrderV1Metadata.TYPE);
                assertThat(item.currentVersionId()).isEqualTo(draft.connectorVersionId());
                // A1：T2 额外暴露最新版本 id/序号/状态，使连接器子页刷新后仍能驱动 校验→发布→退役 生命周期。
                // 草稿/已验证态 currentVersionId 为 null，若无 latestVersionId 则刷新后无法再操作该连接器。
                assertThat(item.latestVersionId()).isEqualTo(draft.connectorVersionId());
                assertThat(item.latestVersionNo()).isEqualTo(1);
                assertThat(item.latestVersionStatus()).isEqualTo(ConnectorVersionStatus.PUBLISHED.getCode());
            });

            // T3：连接器版本详情投影（公共字段 + 配置 + 能力启用态；不含凭据明文）。
            SupplierConfigurationQueryService.ConnectorVersionDetail detail =
                    query.getConnectorVersionDetail(draft.connectorNo(), draft.connectorVersionId());
            assertThat(detail.status()).isEqualTo(ConnectorVersionStatus.PUBLISHED.getCode());
            assertThat(detail.baseUrl()).isEqualTo("https://api.qinameng.com");
            assertThat(detail.adapterConfigJson()).isEqualTo("{}");
            assertThat(detail.capabilities()).extracting(
                    SupplierConfigurationQueryService.CapabilityView::capabilityCode)
                    .containsExactlyInAnyOrder("ISSUE_ASSET", "CREATE_RECHARGE", "QUERY_ORDER",
                            "RECEIVE_CALLBACK", "QUERY_BALANCE");
            assertThat(detail.capabilities()).allMatch(
                    capability -> capability.enabled());

            // T5：状态 + 错误码映射投影。
            SupplierConfigurationQueryService.MappingsView mappings =
                    query.getMappings(draft.connectorVersionId());
            assertThat(mappings.statusMappings()).hasSize(7);
            assertThat(mappings.errorCodeMappings()).hasSize(1);

            // T4：凭据集/项列表——先播种一个 ENVELOPE 之外的 ENV 集与项，secret_ref 含可识别标记。
            LocalDateTime now = LocalDateTime.now();
            CredentialSetDO set = CredentialSetDO.builder()
                    .supplierId(supplierId).setCode("QNM_SET").status((short) 10)
                    .createdAt(now).updatedAt(now).build();
            credentialSetMapper.insert(set);
            credentialItemMapper.insert(CredentialItemDO.builder()
                    .credentialSetId(set.getId()).itemKey("secretKey").providerType("ENV")
                    .secretRef("env:" + SECRET_MARKER).maskedHint("qnm-****")
                    .activeSecretVersion(1).status((short) 10).createdAt(now).updatedAt(now).build());

            List<SupplierConfigurationQueryService.CredentialSetView> credentialSets =
                    query.listCredentialSets(supplierCode);
            assertThat(credentialSets).singleElement().satisfies(view -> {
                assertThat(view.setCode()).isEqualTo("QNM_SET");
                assertThat(view.items()).singleElement().satisfies(item -> {
                    assertThat(item.itemKey()).isEqualTo("secretKey");
                    assertThat(item.providerType()).isEqualTo("ENV");
                    assertThat(item.maskedHint()).isEqualTo("qnm-****");
                    assertThat(item.status()).isEqualTo((short) 10);
                });
            });
            // 脱敏不变量：投影序列化后绝不出现 secret_ref 值或其 env: 引用标记。
            String serialized = objectMapper.writeValueAsString(credentialSets);
            assertThat(serialized).doesNotContain(SECRET_MARKER);
            assertThat(serialized).doesNotContain("env:");

            // A1（§8.2 连接器「凭据引用下拉」数据源）：只读列出可用凭据槽，供连接器草稿绑定 credentialSlotNo。
            // 凭据槽为平台级（sc_credential_slot 无 supplier_id），故不按供应商过滤；投影脱敏：只回
            // slotNo/providerType/maskedHint/status，绝不返回 secret_ref 值（env:QNM_QUERY_TEST_USERKEY）。
            // 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#82-连接器子页e1。
            List<SupplierConfigurationQueryService.CredentialSlotView> slots = query.listActiveCredentialSlots();
            assertThat(slots).extracting(SupplierConfigurationQueryService.CredentialSlotView::slotNo)
                    .contains(slotNo);
            SupplierConfigurationQueryService.CredentialSlotView slotView = slots.stream()
                    .filter(item -> slotNo.equals(item.slotNo())).findFirst().orElseThrow();
            assertThat(slotView.providerType()).isEqualTo("ENV");
            assertThat(slotView.maskedHint()).isEqualTo("qnm-****");
            assertThat(slotView.status()).isEqualTo((short) 10);
            String slotsSerialized = objectMapper.writeValueAsString(slots);
            assertThat(slotsSerialized).doesNotContain("QNM_QUERY_TEST_USERKEY");
            assertThat(slotsSerialized).doesNotContain("env:");
        }
    }

    private ConfigurableApplicationContext applicationContext() {
        java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.connector.allowed-hosts", "api.qinameng.com");
        properties.put("QNM_QUERY_TEST_USERKEY", "integration-secret");
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
            OutboxEventRepository.class, ProductCatalogCommandService.class, ProductCenterTestConfiguration.class,
            QnmGameOrderV1Metadata.class, AdapterMetadataRegistry.class, ClosedJsonSchemaValidator.class,
            EnvironmentCredentialProvider.class, SupplierEndpointPolicy.class,
            SupplierConfigurationCommandService.class, ConnectorValidationRecordService.class,
            ConnectorValidationService.class,
            com.aifuli.module.supplychain.tradecore.supply.application.ConnectorPublicationService.class,
            SupplierConfigurationQueryService.class})
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
