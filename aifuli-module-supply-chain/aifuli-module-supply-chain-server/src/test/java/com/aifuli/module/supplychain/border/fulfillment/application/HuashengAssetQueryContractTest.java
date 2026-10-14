package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.order.application.OrderViewQueryService;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 独占MySQL/Liquibase + 真实Mapper/AES-GCM的双出口资产契约；不调用供应商或资金链。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#72-task7授权查询与有效期展示契约。
 */
class HuashengAssetQueryContractTest {
    static ProjectStandardQueryIntegrationSupport db;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final AesGcmEnvelopeCrypto crypto = new AesGcmEnvelopeCrypto("asset-test-v1",
            Base64.getEncoder().encodeToString(new byte[32]), Base64.getEncoder().encodeToString(new byte[32]));
    OrderViewQueryService admin;
    final List<byte[]> plaintexts = new ArrayList<>();

    @BeforeAll static void start() { db = new ProjectStandardQueryIntegrationSupport(); }
    @AfterAll static void stop() { if (db != null) db.close(); }
    @BeforeEach void setup() throws Exception {
        db.resetData();
        db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.asset(10, 1, 2, "CARD_CODE_SECRET", (short) 10);
        db.jdbc.update("UPDATE sc_fulfillment_request SET status=40,fulfilled_quantity=1 WHERE id=1");
        db.jdbc.update("UPDATE sc_supply_chain_order SET order_status=30 WHERE id=1");
        when(db.crypto().decrypt(any(), any(), any())).thenAnswer(invocation -> {
            byte[] plain = crypto.decrypt(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            plaintexts.add(plain);
            return plain;
        });
        Constructor<?> ctor = OrderViewQueryService.class.getConstructors()[0];
        Object[] args = Arrays.stream(ctor.getParameterTypes()).map(type -> {
            try { return db.context.getBean(type); }
            catch (org.springframework.beans.BeansException missing) { return mock(type); }
        }).toArray();
        admin = (OrderViewQueryService) ctor.newInstance(args);
    }

    static Stream<Arguments> validTimes() { return ProjectStandardAssetPayloadDecoderTest.huashengValidTimes(); }

    @ParameterizedTest @MethodSource("validTimes")
    void bothAuthorizedOutputsPreserveCardAndExpiryAndAudit(String fragment, String state, String raw) throws Exception {
        save(ProjectStandardAssetPayloadDecoderTest.huashengPayload(fragment));
        var project = db.assets().query(db.assetContext(3, 2), "order").getAssets().getFirst();
        var projectJson = json.valueToTree(project);
        var adminCard = adminAssets().path("cardInfos").get(0);
        assertThat(projectJson.path("cardCode").textValue()).isEqualTo(" CODE ");
        assertThat(projectJson.path("cardSecret").textValue()).isEqualTo(" PASS ");
        for (String field : new String[]{"cardCode", "cardSecret", "validTimeState"})
            assertThat(adminCard.path(field)).isEqualTo(projectJson.path(field));
        assertThat(adminCard.path("validTimeState").asText()).isEqualTo(state);
        assertThat(adminCard.path("validTimeRaw").textValue()).isEqualTo(raw);
        assertThat(projectJson.path("validTimeRaw").textValue()).isEqualTo(raw);
        assertThat(adminCard.path("validFrom").isNull()).isTrue();
        assertThat(adminCard.path("validUntil").isNull()).isTrue();
        assertThat(db.jdbc.queryForList("SELECT principal_type,reason_code FROM sc_sensitive_access_audit"))
                .hasSize(3).anySatisfy(row -> assertThat(row).containsEntry("principal_type", "ADMIN_USER")
                        .containsEntry("reason_code", "ADMIN_PLAINTEXT_VIEW"));
        assertThat(plaintexts).hasSize(2).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
    }

    @Test void wrongProjectCannotDecryptAndHasRejectedAudit() {
        save(ProjectStandardAssetPayloadDecoderTest.huashengPayload(",\"validTime\":\"长期有效\""));
        clearInvocations(db.crypto());
        assertThatThrownBy(() -> db.assets().query(db.assetContext(4, 2), "order"))
                .isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
        verifyNoInteractions(db.crypto());
        assertThat(db.jdbc.queryForList("SELECT principal_type,project_id,result_status FROM sc_sensitive_access_audit"))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("principal_type", "PROJECT_APPLICATION")
                        .containsEntry("project_id", 4L).containsEntry("result_status", 20));
    }

    @Test void bothAuthorizedOutputsReadType202SingleCredentialFromEncryptedAsset() throws Exception {
        save("""
                {"payloadSchemaVersion":1,"assetType":"CARD_CODE_SECRET","cardCode":" C202 ","cardSecret":" C202 ",
                "supplierPayload":{"adapterType":"HUA_SHENG_ORDER_V1","type":202,"code":" C202 ","pass":"","validTime":null}}
                """);
        var project = db.assets().query(db.assetContext(3, 2), "order").getAssets().getFirst();
        var adminCard = adminAssets().path("cardInfos").get(0);
        assertThat(project.getCardCode()).isEqualTo(" C202 ");
        assertThat(project.getCardSecret()).isEqualTo(" C202 ");
        assertThat(adminCard.path("cardCode").textValue()).isEqualTo(" C202 ");
        assertThat(adminCard.path("cardSecret").textValue()).isEqualTo(" C202 ");
        assertThat(plaintexts).hasSize(2).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {",\"validTime\":[]", ",\"validTime\":{}", ",\"validTime\":201",
            ",\"validTime\":false", ",\"validTime\":null,\"validTime\":\"长期有效\"",
            ",\"unexpected\":\"delivery-secret\""})
    void invalidSupplierPayloadIsRejectedAndBothFailuresAreAudited(String fragment) {
        save(ProjectStandardAssetPayloadDecoderTest.huashengPayload(fragment));
        assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(this::adminAssets).isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForList("SELECT result_status,reason_code FROM sc_sensitive_access_audit"))
                .hasSize(3).allSatisfy(row -> assertThat(row.get("result_status")).isEqualTo(30))
                .anySatisfy(row -> assertThat(row).containsEntry("reason_code", "PAYLOAD_CORRUPTED"));
        assertThat(plaintexts).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
    }

    @Test void auditDatabaseFailurePreventsBothOutputs() {
        save(ProjectStandardAssetPayloadDecoderTest.huashengPayload(""));
        db.jdbc.execute("ALTER TABLE sc_sensitive_access_audit ADD CONSTRAINT task7_reject_audit CHECK (resource_id < 0)");
        try {
            assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(this::adminAssets).isInstanceOf(IllegalStateException.class);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sc_sensitive_access_audit", Integer.class)).isZero();
        } finally { db.jdbc.execute("ALTER TABLE sc_sensitive_access_audit DROP CHECK task7_reject_audit"); }
        assertThat(plaintexts).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
    }

    @Test void encryptedAssetCannotBeMovedAcrossAadAndFailuresAreAudited() {
        save(ProjectStandardAssetPayloadDecoderTest.huashengPayload(""));
        db.jdbc.update("UPDATE sc_fulfillment_asset SET sequence_no=2 WHERE id=10");
        assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(this::adminAssets).isInstanceOf(IllegalStateException.class);
        assertThat(db.jdbc.queryForList("SELECT reason_code FROM sc_sensitive_access_audit"))
                .hasSize(3).allSatisfy(row -> assertThat(row).containsEntry("reason_code", "DECRYPT_FAILED"));
    }

    private void save(String payload) {
        byte[] plain = payload.getBytes(StandardCharsets.UTF_8);
        try {
            var encrypted = crypto.encrypt(plain, "10|1|CARD_CODE_SECRET".getBytes(StandardCharsets.UTF_8));
            db.jdbc.update("UPDATE sc_fulfillment_asset SET encrypted_payload=?,key_version=? WHERE id=10",
                    encrypted.ciphertext(), encrypted.keyVersion());
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    private com.fasterxml.jackson.databind.JsonNode adminAssets() throws Exception {
        // 走真实管理详情服务入口和组装链，确保CardInfo确实进入页面使用的items契约。
        return json.valueToTree(admin.get("SC_1", "PLATFORM", 9001L).items().getFirst());
    }
}
