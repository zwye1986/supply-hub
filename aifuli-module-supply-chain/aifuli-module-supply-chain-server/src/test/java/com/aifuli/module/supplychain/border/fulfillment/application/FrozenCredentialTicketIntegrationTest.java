package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.security.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialSlotStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialStatus;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 冻结材料装配的真实数据库门禁，不注册/访问供应商；见SUP3详细设计§3.2。 */
class FrozenCredentialTicketIntegrationTest {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("frozen_ticket").withUsername("supply_chain").withPassword("supply_chain");
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static final List<char[]> MATERIAL = new CopyOnWriteArrayList<>();
    static final AtomicReference<CountDownLatch> ENTERED = new AtomicReference<>(), RELEASE = new AtomicReference<>();
    static final AtomicBoolean FAIL_COMMIT = new AtomicBoolean();

    @BeforeAll static void start() {
        MYSQL.start();
        context = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).properties(Map.of(
                "aifuli.supply-chain.enabled", true,
                "aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl(),
                "aifuli.supply-chain.datasource.username", MYSQL.getUsername(),
                "aifuli.supply-chain.datasource.password", MYSQL.getPassword(),
                "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")).run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", javax.sql.DataSource.class));
    }
    @AfterAll static void stop() { if (context != null) context.close(); MYSQL.stop(); }
    @AfterEach void reset() {
        ENTERED.set(null);
        FAIL_COMMIT.set(false);
        var latch = RELEASE.getAndSet(null);
        if (latch != null) latch.countDown();
        MATERIAL.forEach(chars -> Arrays.fill(chars, '\0'));
        MATERIAL.clear();
    }

    @Test void slotVersionIsAnExactStringAndRotationProviderChangesOrDeactivationRejectOriginalIdentity() {
        var slot = slot();
        var identity = new FrozenCredentialSlotIdentity(slot.getId(), "ENV", "01");
        var config = config(slot.getId(), null, List.of());
        var metadata = metadata(CredentialBindingMode.SINGLE_SLOT, Set.of("md5Key"));
        try (var ticket = ticket(config, metadata, identity)) {
            String actual = ticket.resolvedCredentialMaterial().use("md5Key", String::new);
            assertThat(actual).isEqualTo("synthetic-01");
            assertThat(ticket.secretKey()).isNull();
            assertThat(ticket.credentialMaterial()).isNull();
        }
        assertThat(MATERIAL).hasSize(1).allSatisfy(chars -> assertThat(chars).containsOnly('\0'));
        assertThatThrownBy(() -> ticket(config, metadata, new FrozenCredentialSlotIdentity(slot.getId() + 1, "ENV", "01")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ticket(config(slot.getId(), 42L, List.of()), metadata, identity))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ticket(config, metadata(CredentialBindingMode.CREDENTIAL_SET, Set.of("md5Key")), identity))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ticket(config, metadata(CredentialBindingMode.SINGLE_SLOT, Set.of("md5Key", "extraKey")), identity))
                .isInstanceOf(IllegalStateException.class);
        when(metadata.adapterType()).thenReturn("ANOTHER_ADAPTER");
        assertThatThrownBy(() -> ticket(config, metadata, identity)).isInstanceOf(IllegalStateException.class);
        when(metadata.adapterType()).thenReturn("TEST_KEYED");
        assertThatThrownBy(() -> ticket(config, metadata, new FrozenCredentialSlotIdentity(slot.getId(), "ENV", "1")))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE sc_credential_slot SET active_secret_version='1' WHERE id=?", slot.getId());
        assertThatThrownBy(() -> ticket(config, metadata, identity)).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE sc_credential_slot SET active_secret_version='01',provider_type='OTHER' WHERE id=?", slot.getId());
        assertThatThrownBy(() -> ticket(config, metadata, identity)).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE sc_credential_slot SET provider_type='ENV',status=? WHERE id=?", CredentialSlotStatus.SUSPENDED.getCode(), slot.getId());
        assertThatThrownBy(() -> ticket(config, metadata, identity)).isInstanceOf(IllegalStateException.class);
        assertThat(MATERIAL).hasSize(1);
    }

    @Test void currentReadLockCoversIdentityVerificationAndProviderResolution() throws Exception {
        var slot = slot();
        var identity = new FrozenCredentialSlotIdentity(slot.getId(), "ENV", "01");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        ENTERED.set(entered); RELEASE.set(release);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var reading = threads.submit(() -> context.getBean(FrozenCredentialSlotResolver.class).resolve(identity));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var started = new CountDownLatch(1);
            var changing = threads.submit(() -> {
                started.countDown();
                return jdbc.update("UPDATE sc_credential_slot SET active_secret_version='2' WHERE id=?", slot.getId());
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> changing.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            char[] material = reading.get(10, TimeUnit.SECONDS);
            try { assertThat(material).isEqualTo("synthetic-01".toCharArray()); }
            finally { Arrays.fill(material, '\0'); }
            assertThat(changing.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(() -> context.getBean(FrozenCredentialSlotResolver.class).resolve(identity))
                    .isInstanceOf(IllegalStateException.class);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void setKeepsDeclaredKeyNamesAndIntegerVersionsWithoutLegacyAppSecretProjection() {
        var config = setConfig();
        var identities = config.frozenCredentialItems();
        var metadata = metadata(CredentialBindingMode.CREDENTIAL_SET, Set.of("merchantKey", "privateKey"));
        try (var ticket = ticket(config, metadata, null)) {
            String actual = ticket.resolvedCredentialMaterial().use("merchantKey", String::new);
            assertThat(actual).isEqualTo("synthetic-merchantKey");
            assertThat(ticket.frozenCredentialItems()).containsExactlyElementsOf(identities);
        }
        assertThat(MATERIAL).hasSize(2).allSatisfy(chars -> assertThat(chars).containsOnly('\0'));
        jdbc.update("UPDATE sc_credential_item SET active_secret_version=8 WHERE id=?", identities.get(1).itemId());
        assertThatThrownBy(() -> ticket(config, metadata, null)).isInstanceOf(IllegalStateException.class);
        assertThat(MATERIAL).hasSize(2);
    }

    @Test void failedResolverTransactionErasesMaterialBeforeFactoryCanTakeOwnership() {
        var slot = slot();
        var identity = new FrozenCredentialSlotIdentity(slot.getId(), "ENV", "01");
        var set = setConfig();
        FAIL_COMMIT.set(true);
        assertThatThrownBy(() -> ticket(config(slot.getId(), null, List.of()),
                metadata(CredentialBindingMode.SINGLE_SLOT, Set.of("md5Key")), identity))
                .isInstanceOf(IllegalStateException.class).hasMessage("synthetic commit failure");
        assertThatThrownBy(() -> ticket(set, metadata(CredentialBindingMode.CREDENTIAL_SET, Set.of("merchantKey", "privateKey")), null))
                .isInstanceOf(IllegalStateException.class).hasMessage("synthetic commit failure");
        assertThat(MATERIAL).hasSize(3).allSatisfy(chars -> assertThat(chars).containsOnly('\0'));
    }

    static PublishedConnectorConfiguration setConfig() {
        var now = LocalDateTime.now();
        var set = CredentialSetDO.builder().supplierId(19L).setCode(UUID.randomUUID().toString().substring(0, 20))
                .status(CredentialStatus.ACTIVE.getCode()).createdAt(now).updatedAt(now).build();
        context.getBean(CredentialSetMapper.class).insert(set);
        var identities = new ArrayList<FrozenCredentialItemIdentity>();
        for (String key : List.of("merchantKey", "privateKey")) {
            var item = CredentialItemDO.builder().credentialSetId(set.getId()).itemKey(key).providerType("ENV")
                    .secretRef("env:SYNTHETIC").activeSecretVersion(key.equals("merchantKey") ? 3 : 7)
                    .status(CredentialStatus.ACTIVE.getCode()).createdAt(now).updatedAt(now).build();
            context.getBean(CredentialItemMapper.class).insert(item);
            identities.add(new FrozenCredentialItemIdentity(item.getId(), key, "ENV", item.getActiveSecretVersion()));
        }
        return config(null, set.getId(), identities);
    }

    static CredentialSlotDO slot() {
        var now = LocalDateTime.now();
        var slot = CredentialSlotDO.builder().slotNo(UUID.randomUUID().toString().replace("-", ""))
                .providerType("ENV").secretRef("env:SYNTHETIC").activeSecretVersion("01")
                .status(CredentialSlotStatus.ACTIVE.getCode()).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(CredentialSlotMapper.class).insert(slot);
        return slot;
    }
    static PublishedConnectorConfiguration config(Long slot, Long set, List<FrozenCredentialItemIdentity> items) {
        return new PublishedConnectorConfiguration("CON", 71L, 1, "TEST_KEYED", "https://supplier.invalid", "MERCHANT",
                slot, null, Set.of(), set, slot != null ? CredentialBindingMode.SINGLE_SLOT : CredentialBindingMode.CREDENTIAL_SET,
                1500, 2501, Map.of(), items);
    }
    static AdapterMetadata metadata(CredentialBindingMode mode, Set<String> keys) {
        var metadata = mock(AdapterMetadata.class);
        when(metadata.adapterType()).thenReturn("TEST_KEYED");
        when(metadata.credentialBindingMode()).thenReturn(mode);
        when(metadata.credentialKeys()).thenReturn(keys);
        return metadata;
    }
    static OperationTicket ticket(PublishedConnectorConfiguration config, AdapterMetadata metadata, FrozenCredentialSlotIdentity identity) {
        return context.getBean(FulfillmentOperationTicketFactory.class).createWithFrozenCredentials(
                FulfillmentOperationDO.builder().operationNo("OP").adapterStepCode("SEND").build(),
                BOrderDO.builder().bOrderNo("BO").connectorVersionId(71L).supplierSettlementUnitPrice(884L).currency("CNY").build(),
                FulfillmentRequestDO.builder().build(), SupplierDO.builder().supplierCode("SUP").build(), null,
                config, metadata, identity, FulfillmentOperationKind.QUERY_ORDER, null, "TRACE");
    }

    static void failCommitIfRequested() {
        if (FAIL_COMMIT.get()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("synthetic commit failure"); }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, FulfillmentOperationTicketFactory.class, FulfillmentCallbackProperties.class,
            FrozenCredentialSlotResolver.class, FrozenCredentialSetResolver.class})
    static class Config {
        @Bean CredentialProvider provider() { return new CredentialProvider() {
            public boolean supports(String type) { return "ENV".equals(type); }
            public boolean isResolvable(CredentialSlotDO slot) { return true; }
            public char[] resolve(CredentialSlotDO slot) {
                var entered = ENTERED.get(); var release = RELEASE.get();
                if (entered != null) {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("handshake timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted", e); }
                }
                failCommitIfRequested();
                char[] chars = ("synthetic-" + slot.getActiveSecretVersion()).toCharArray(); MATERIAL.add(chars); return chars;
            }
        }; }
        @Bean CredentialItemResolver itemProvider() { return new CredentialItemResolver() {
            public boolean supports(String type) { return "ENV".equals(type); }
            public char[] resolve(CredentialItemDO item) {
                failCommitIfRequested();
                char[] chars = ("synthetic-" + item.getItemKey()).toCharArray(); MATERIAL.add(chars); return chars;
            }
        }; }
    }
}
