package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.*;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.security.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.supply.application.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

/** 独占真实MySQL从配置服务发布并验证冻结解析的锁边界；见华昇生产闭环设计§3.3。 */
class HuashengFrozenConfigurationIntegrationTest {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("huasheng_frozen").withUsername("supply_chain").withPassword("supply_chain");
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static final AtomicReference<CountDownLatch> ENTERED = new AtomicReference<>();
    static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>();
    static final List<char[]> MATERIAL = new CopyOnWriteArrayList<>();

    @BeforeAll static void start() {
        MYSQL.start();
        var properties = new LinkedHashMap<String, Object>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", MYSQL.getUsername());
        properties.put("aifuli.supply-chain.datasource.password", MYSQL.getPassword());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.connector.allowed-hosts", "huasheng.example.com");
        properties.put("aifuli.supply-chain.security.key-version", "v1");
        properties.put("aifuli.supply-chain.security.envelope-key-base64", Base64.getEncoder().encodeToString(new byte[32]));
        properties.put("aifuli.supply-chain.security.fingerprint-key-base64", Base64.getEncoder().encodeToString(new byte[32]));
        context = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).properties(properties).run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", javax.sql.DataSource.class));
    }
    @AfterAll static void stop() { if (context != null) context.close(); MYSQL.stop(); }
    @AfterEach void reset() { ENTERED.set(null); var latch = RELEASE.getAndSet(null); if (latch != null) latch.countDown(); MATERIAL.clear(); }

    @Test void realPublicationProjectsFrozenIdentitiesAndOnlyRequestedSecrets() {
        var fixture = published();
        var runtime = context.getBean(PublishedConnectorConfigurationResolver.class).resolve(fixture.draft.connectorVersionId());
        assertThat(runtime.merchantAccount()).isEqualTo("1234567890123456");
        assertThat(runtime.frozenCredentialItems()).hasSize(3);
        assertThatThrownBy(() -> runtime.frozenCredentialItems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(runtime.extraParameters()).containsEntry("expireTime", "60");
        var values = context.getBean(FrozenCredentialSetResolver.class).resolve(fixture.setId, runtime.frozenCredentialItems(), HuashengOrderV1Metadata.OUTBOUND_KEYS);
        try {
            assertThat(values).containsOnlyKeys("appSecret", "privateKey");
            assertThat(values.values()).allSatisfy(value -> assertThat(value).isNotEmpty());
            assertThat(runtime.toString()).doesNotContain("synthetic-appSecret", "synthetic-privateKey");
        } finally { values.values().forEach(value -> Arrays.fill(value, '\0')); }
        jdbc.update("UPDATE sc_credential_item SET active_secret_version=2 WHERE credential_set_id=? AND item_key='privateKey'", fixture.setId);
        assertThatThrownBy(() -> context.getBean(PublishedConnectorConfigurationResolver.class).resolve(fixture.draft.connectorVersionId()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> context.getBean(FrozenCredentialSetResolver.class).resolve(fixture.setId, runtime.frozenCredentialItems(), HuashengOrderV1Metadata.OUTBOUND_KEYS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void versionUpdateCannotInterleaveVerificationAndProviderResolution() throws Exception {
        var fixture = published();
        var runtime = context.getBean(PublishedConnectorConfigurationResolver.class).resolve(fixture.draft.connectorVersionId());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        ENTERED.set(entered); RELEASE.set(release);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var reading = threads.submit(() -> context.getBean(FrozenCredentialSetResolver.class).resolve(fixture.setId,
                    runtime.frozenCredentialItems(), HuashengOrderV1Metadata.OUTBOUND_KEYS));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var started = new CountDownLatch(1);
            var changing = threads.submit(() -> {
                started.countDown();
                return jdbc.update("UPDATE sc_credential_item SET active_secret_version=2 WHERE credential_set_id=? AND item_key='privateKey'", fixture.setId);
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> changing.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            var values = reading.get(10, TimeUnit.SECONDS);
            try { assertThat(new String(values.get("privateKey"))).isEqualTo("synthetic-privateKey"); }
            finally { values.values().forEach(value -> Arrays.fill(value, '\0')); }
            assertThat(changing.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(() -> context.getBean(FrozenCredentialSetResolver.class).resolve(fixture.setId,
                    runtime.frozenCredentialItems(), HuashengOrderV1Metadata.OUTBOUND_KEYS)).isInstanceOf(IllegalStateException.class);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void mappingEditedAfterValidationCannotPublishOrExecute() {
        var fixture = draft();
        validate(fixture.draft);
        jdbc.update("UPDATE sc_status_mapping_definition SET terminal_flag=1 WHERE connector_version_id=? AND external_status='status=SUCCESS'", fixture.draft.connectorVersionId());
        assertThatThrownBy(() -> publish(fixture.draft)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM sc_supplier_connector WHERE connector_no=?", Long.class, fixture.draft.connectorNo())).isNull();
        jdbc.update("UPDATE sc_status_mapping_definition SET terminal_flag=0 WHERE connector_version_id=?", fixture.draft.connectorVersionId());
        publish(fixture.draft);
        jdbc.update("UPDATE sc_status_mapping_definition SET fact_type='SUCCEEDED' WHERE connector_version_id=? AND external_status='status=SUCCESS'", fixture.draft.connectorVersionId());
        assertThatThrownBy(() -> context.getBean(PublishedConnectorConfigurationResolver.class).resolve(fixture.draft.connectorVersionId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void resolutionFailureErasesAlreadyAcquiredMaterialAndChecksAllIdentities() {
        var fixture = published();
        var runtime = context.getBean(PublishedConnectorConfigurationResolver.class).resolve(fixture.draft.connectorVersionId());
        jdbc.update("UPDATE sc_credential_item SET secret_ref='env:FAIL' WHERE credential_set_id=? AND item_key='privateKey'", fixture.setId);
        MATERIAL.clear();
        assertThatThrownBy(() -> context.getBean(FrozenCredentialSetResolver.class).resolve(fixture.setId, runtime.frozenCredentialItems(), HuashengOrderV1Metadata.OUTBOUND_KEYS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MATERIAL).isNotEmpty().allSatisfy(chars -> assertThat(chars).containsOnly('\0'));
        jdbc.update("UPDATE sc_credential_item SET active_secret_version=2 WHERE credential_set_id=? AND item_key='callbackPublicKey'", fixture.setId);
        MATERIAL.clear();
        assertThatThrownBy(() -> context.getBean(FrozenCredentialSetResolver.class).resolve(fixture.setId, runtime.frozenCredentialItems(), HuashengOrderV1Metadata.OUTBOUND_KEYS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MATERIAL).isEmpty();
    }

    static Fixture published() { var fixture = draft(); validate(fixture.draft); publish(fixture.draft); return fixture; }
    static Fixture draft() {
        var config = context.getBean(SupplierConfigurationCommandService.class);
        String supplier = config.createSupplier(new SupplierConfigurationCommandService.CreateSupplierCommand(null, "华昇夹具", "华昇夹具", "STANDARD_SUPPLIER", null, 1L));
        var credentials = context.getBean(SupplierCredentialCommandService.class);
        long setId = credentials.createCredentialSet(new SupplierCredentialCommandService.CreateCredentialSetCommand(supplier, "COMPLETE", 1L));
        for (String key : new TreeSet<>(new HuashengOrderV1Metadata().credentialKeys())) {
            credentials.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(supplier, "COMPLETE", key, "ENV", "env:TEST_" + key, null, "masked", 1, 1L));
        }
        var draft = config.createConnectorDraft(new SupplierConfigurationCommandService.CreateConnectorDraftCommand(supplier,
                "CON_" + UUID.randomUUID(), HuashengOrderV1Metadata.TYPE, "https://huasheng.example.com", "1234567890123456",
                null, "V1", HuashengOrderV1Metadata.CALLBACK_PATH, 1500, 2501, null, null, "{}", 1L, "COMPLETE", "{\"expireTime\":60}"));
        config.initializeMappings(draft.connectorVersionId(), 1L);
        return new Fixture(setId, draft);
    }
    static void validate(SupplierConfigurationCommandService.DraftConnectorResult draft) {
        context.getBean(ConnectorValidationService.class).validate(new ConnectorValidationService.ValidateConnectorCommand(draft.connectorVersionId(),
                new HuashengOrderV1Metadata().capabilities().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()), Duration.ofMinutes(10), 1L, "夹具", "trace"));
    }
    static void publish(SupplierConfigurationCommandService.DraftConnectorResult draft) {
        context.getBean(ConnectorPublicationService.class).publish(new ConnectorPublicationService.PublishConnectorCommand(draft.connectorNo(), draft.connectorVersionId(), 0, 1L, "夹具", "trace", null, null));
    }
    record Fixture(long setId, SupplierConfigurationCommandService.DraftConnectorResult draft) {}

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, UlidGenerator.class, BusinessNoGenerator.class, OutboxEventRepository.class,
            SupplierConfigurationCommandService.class, ConnectorCredentialBindingValidator.class, ConnectorValidationService.class,
            ConnectorValidationRecordService.class, ConnectorPublicationService.class, PublishedConnectorConfigurationResolver.class,
            AdapterMetadataRegistry.class, ClosedJsonSchemaValidator.class, HuashengOrderV1Metadata.class, SupplierCredentialCommandService.class,
            AesGcmEnvelopeCrypto.class, DefaultCredentialSetResolver.class, FrozenCredentialSetResolver.class, SupplierEndpointPolicy.class})
    static class Config {
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean CredentialItemResolver observedProvider() { return new CredentialItemResolver() {
            public boolean supports(String type) { return "ENV".equals(type); }
            public char[] resolve(CredentialItemDO item) {
                if (item.getSecretRef().equals("env:FAIL")) throw new IllegalStateException("synthetic failure");
                var entered = ENTERED.get(); var release = RELEASE.get();
                if (entered != null && item.getItemKey().equals("appSecret")) {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("handshake timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted", e); }
                }
                char[] chars = ("synthetic-" + item.getItemKey()).toCharArray(); MATERIAL.add(chars); return chars;
            }
        }; }
    }
}
