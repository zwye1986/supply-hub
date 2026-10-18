package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAssetMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.domain.CardOrderSummaryPolicy;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

/**
 * 真实private persistAssets + Mapper/事务/AES-GCM/HMAC基础门禁，不走通用资金终结。
 * 测试反射仅访问本仓持久方法，协议卡片仍由真实A parser产生。生产闭环设计 §7.1。
 */
class HuashengAssetPersistenceIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("huasheng_assets").withUsername("supply_chain").withPassword("supply_chain");
    private static final String EXTERNAL_URL = System.getProperty("supply-chain.test.jdbc-url");
    private static ConfigurableApplicationContext context;
    private FulfillmentAssetMapper mapper;
    private TransactionTemplate tx;
    private SupplierOrderLifecycle lifecycle;
    private AesGcmEnvelopeCrypto storageCrypto;
    private ListAppender<ILoggingEvent> logs;
    private final ObjectMapper json = new ObjectMapper();
    private final HuashengType201AssetCodec codec = new HuashengType201AssetCodec();
    private final AesGcmEnvelopeCrypto crypto = new AesGcmEnvelopeCrypto("asset-it-v1",
            Base64.getEncoder().encodeToString(new byte[32]), Base64.getEncoder().encodeToString(new byte[32]));

    @BeforeAll
    static void start() {
        if (EXTERNAL_URL == null) MYSQL.start();
        context = new SpringApplicationBuilder(SupplyChainDataSourceConfiguration.class).web(WebApplicationType.NONE)
                .properties(Map.of("aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", EXTERNAL_URL == null ? MYSQL.getJdbcUrl() : EXTERNAL_URL,
                        "aifuli.supply-chain.datasource.username", EXTERNAL_URL == null ? MYSQL.getUsername()
                                : System.getProperty("supply-chain.test.jdbc-username", "root"),
                        "aifuli.supply-chain.datasource.password", EXTERNAL_URL == null ? MYSQL.getPassword()
                                : System.getProperty("supply-chain.test.jdbc-password", ""),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")).run();
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (EXTERNAL_URL == null && MYSQL.isRunning()) MYSQL.stop();
    }

    @BeforeEach
    void setup() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(SupplierOrderLifecycle.class)).addAppender(logs);
        mapper = context.getBean(FulfillmentAssetMapper.class);
        mapper.delete(new LambdaQueryWrapper<>());
        tx = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        storageCrypto = spy(crypto);
        // 未用依赖刻意留空；若资产方法错误调用资金或聚合终结，将直接失败。
        lifecycle = new SupplierOrderLifecycle(null, null, null, null, mapper, null, null, null, null, null,
                new CardOrderSummaryPolicy(), storageCrypto, storageCrypto, new BusinessNoGenerator(new UlidGenerator()),
                null, json, null, null);
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(SupplierOrderLifecycle.class)).detachAppender(logs);
        logs.stop();
    }

    @Test
    void encryptInsertDecryptPreservesEveryValidTimeStateAndUsesOriginalAssetAad() throws Exception {
        int index = 0;
        for (String suffix : List.of("", ",\"validTime\":null", ",\"validTime\":\"\"", ",\"validTime\":\" 长期有效 \"")) {
            int i = ++index;
            var fact = fact(" C_SECRET_" + i + " ", " P_SECRET_" + i + " ", ",\"id\":9" + suffix);
            persist(i, List.of(fact));
            var row = row(i);
            assertThat(row.getExternalAssetId()).isNull();
            assertThat(row.getValidFrom()).isNull(); assertThat(row.getValidUntil()).isNull();
            assertThat(row.getKeyVersion()).isEqualTo("asset-it-v1");
            assertThat(row.getMaskedValue()).isEqualTo("****");
            assertThat(new String(row.getEncryptedPayload(), StandardCharsets.ISO_8859_1))
                    .doesNotContain("C_SECRET_", "P_SECRET_");
            byte[] plaintext = crypto.decrypt(row.getEncryptedPayload(), row.getKeyVersion(), aad(i));
            try { assertThat(plaintext).containsExactly(fact.protectedPayload()); }
            finally { Arrays.fill(plaintext, (byte) 0); }
            assertThatThrownBy(() -> crypto.decrypt(row.getEncryptedPayload(), row.getKeyVersion(), aad(i + 100)))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertNoFunds();
    }

    @Test
    void repeatedCompletePayloadIsIdempotentButMetadataAndCredentialConflictsCannotOverwrite() throws Exception {
        var original = fact(" C_SECRET ", " P_SECRET ", ",\"validTime\":null");
        persist(20, List.of(original));
        byte[] ciphertext = row(20).getEncryptedPayload();
        persist(20, List.of(original));
        assertThat(mapper.selectCount(new LambdaQueryWrapper<>())).isEqualTo(1);
        assertThat(row(20).getEncryptedPayload()).containsExactly(ciphertext);
        for (var conflicting : List.of(fact(" C_SECRET ", " P_SECRET ", ""),
                fact(" C_SECRET ", " DIFFERENT_SECRET ", ",\"validTime\":null"),
                fact(" C_SECRET ", " P_SECRET ", ",\"validTime\":\"\""),
                fact(" C_SECRET ", " P_SECRET ", ",\"id\":9,\"validTime\":null"),
                fact(" C_SECRET ", " P_SECRET ", ",\"crc\":null,\"validTime\":null"),
                fact(" C_SECRET ", " P_SECRET ", ",\"url\":\"\",\"validTime\":null"),
                fact(" C_SECRET ", " P_SECRET ", ",\"urlPass\":\" \",\"validTime\":null"))) {
            assertThatThrownBy(() -> persist(20, List.of(conflicting))).isInstanceOf(IllegalStateException.class)
                    .hasMessage("华昇type201资产事实冲突");
            assertThat(row(20).getEncryptedPayload()).containsExactly(ciphertext);
        }
        assertNoFunds();
    }

    @Test
    void crossOrderDuplicateCredentialsTriggerRealUniqueConstraintAndRollbackAllEarlierWrites() throws Exception {
        var original = fact("DUP_CODE_SECRET", "DUP_PASS_SECRET", ",\"id\":9");
        persist(30, List.of(original));
        var newCard = fact("OTHER_CODE_SECRET", "OTHER_PASS_SECRET", "");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            invoke(31, List.of(newCard));
            invoke(32, List.of(fact("DUP_CODE_SECRET", "DUP_PASS_SECRET", ",\"id\":10,\"validTime\":\"长期\"")));
        })).isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uk_sc_fulfillment_asset_fingerprint");
        assertThat(mapper.selectCount(new LambdaQueryWrapper<>())).isEqualTo(1);
        assertThat(row(31)).isNull(); assertThat(row(32)).isNull();
        assertNoFunds();
    }

    @Test
    void sameSupplierCardIdWithDifferentCredentialsAndColonCollisionPairsRemainDistinct() throws Exception {
        persist(40, List.of(fact("a:b", "c", ",\"id\":9")));
        persist(41, List.of(fact("a", "b:c", ",\"id\":9")));
        assertThat(mapper.selectCount(new LambdaQueryWrapper<>())).isEqualTo(2);
        assertThat(row(40).getFingerprint()).isNotEqualTo(row(41).getFingerprint());
        assertThat(row(40).getExternalAssetId()).isNull(); assertThat(row(41).getExternalAssetId()).isNull();
        byte[] input = codec.fingerprintInput(7, fact("a:b", "c", ",\"id\":9"));
        try { assertThat(row(40).getFingerprint()).containsExactly(crypto.fingerprint("FULFILLMENT_ASSET", input)); }
        finally { Arrays.fill(input, (byte) 0); }
        assertNoFunds();
    }

    @Test
    void forgedProtectedPayloadMustBeRejectedBeforeAnyInsert() throws Exception {
        var original = fact("C_SECRET", "P_SECRET", "");
        byte[] corrupted = original.protectedPayload();
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(corrupted);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get("supplierPayload")).put("code", "DIFFERENT_SECRET");
        var forged = OperationAssetFact.builder().sequenceNo(1).assetType("CARD_CODE_SECRET")
                .cardNo(original.cardNo()).cardSecret(original.cardSecret()).protectedPayload(json.writeValueAsBytes(tree)).build();
        assertThatThrownBy(() -> persist(50, List.of(forged))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("华昇type201资产无效").hasNoCause();
        assertThat(mapper.selectCount(new LambdaQueryWrapper<>())).isZero();
        assertNoFunds();
    }

    @Test
    void successLogsOnlyAfterCommitNotOnRollbackOrIdempotentRepeatAndNeverContainSecrets() throws Exception {
        var card = fact("C_SECRET", "P_SECRET", "");
        tx.executeWithoutResult(status -> {
            invoke(60, List.of(card));
            assertThat(logs.list).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(logs.list).isEmpty();
        assertThat(row(60)).isNull();
        tx.executeWithoutResult(status -> {
            invoke(60, List.of(card));
            assertThat(logs.list).isEmpty();
        });
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("[华昇资产持久化][事务提交确认]")
                .doesNotContain("C_SECRET", "P_SECRET", "supplierPayload", "\n");
        persist(60, List.of(card));
        assertThat(logs.list).hasSize(1);
        assertNoFunds();
    }

    @Test
    void protectedAssetCannotBePersistedOutsideAggregateTransaction() {
        var card = fact("C_SECRET", "P_SECRET", "");
        assertThatThrownBy(() -> invoke(70, List.of(card))).isInstanceOf(IllegalStateException.class)
                .hasMessage("华昇资产持久化必须处于聚合事务内");
        assertThat(mapper.selectCount(new LambdaQueryWrapper<>())).isZero();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void temporaryEncryptionAndFingerprintInputsAreErasedOnSuccessAndConflict() {
        var original = fact("C_SECRET", "P_SECRET", "");
        persist(80, List.of(original));
        var plaintext = ArgumentCaptor.forClass(byte[].class);
        verify(storageCrypto).encrypt(plaintext.capture(), any());
        assertThat(plaintext.getValue()).containsOnly((byte) 0);
        persist(80, List.of(original));
        assertThatThrownBy(() -> persist(80, List.of(fact("C_SECRET", "P_SECRET", ",\"validTime\":null"))))
                .hasMessage("华昇type201资产事实冲突");
        var canonical = ArgumentCaptor.forClass(byte[].class);
        verify(storageCrypto, times(3)).fingerprint(eq("FULFILLMENT_ASSET"), canonical.capture());
        for (byte[] bytes : canonical.getAllValues()) assertThat(bytes).containsOnly((byte) 0);
        assertThat(codec.validate(original)).containsExactly(original.protectedPayload());
    }

    private OperationAssetFact fact(String code, String pass, String suffix) {
        return codec.toFact(HuashengType201AssetCodecTest.card("{\"type\":201,\"code\":\"" + code
                + "\",\"pass\":\"" + pass + "\"" + suffix + "}"));
    }

    private void persist(long bOrderId, List<OperationAssetFact> facts) { tx.executeWithoutResult(status -> invoke(bOrderId, facts)); }

    private void invoke(long bOrderId, List<OperationAssetFact> facts) {
        try {
            Method persist = SupplierOrderLifecycle.class.getDeclaredMethod("persistAssets", BOrderDO.class,
                    FulfillmentOperationDO.class, FulfillmentRequestDO.class, SupplyChainOrderDO.class,
                    OperationOutcome.class, LocalDateTime.class);
            persist.setAccessible(true);
            persist.invoke(lifecycle, BOrderDO.builder().id(bOrderId).bOrderNo("BO_IT_" + bOrderId).supplierId(7L).build(),
                    FulfillmentOperationDO.builder().id(bOrderId).build(), FulfillmentRequestDO.builder().id(bOrderId).buyerId(8L).build(),
                    SupplyChainOrderDO.builder().id(bOrderId).build(), OperationOutcome.builder()
                            .outcomeClass(OperationOutcomeClass.SUCCEEDED).assets(facts).build(),
                    LocalDateTime.of(2026, 10, 9, 12, 0));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private FulfillmentAssetDO row(long bOrderId) {
        return mapper.selectOne(new LambdaQueryWrapper<FulfillmentAssetDO>().eq(FulfillmentAssetDO::getBOrderId, bOrderId));
    }

    private byte[] aad(long bOrderId) { return (bOrderId + "|1|CARD_CODE_SECRET").getBytes(StandardCharsets.UTF_8); }

    private void assertNoFunds() throws Exception {
        try (var connection = context.getBean("supplyChainDataSource", DataSource.class).getConnection();
             var statement = connection.createStatement()) {
            for (String table : List.of("sc_fund_reservation", "sc_account_ledger", "sc_supplier_settlement_ledger")) {
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertThat(result.next()).isTrue(); assertThat(result.getLong(1)).isZero();
                }
            }
        }
    }
}
