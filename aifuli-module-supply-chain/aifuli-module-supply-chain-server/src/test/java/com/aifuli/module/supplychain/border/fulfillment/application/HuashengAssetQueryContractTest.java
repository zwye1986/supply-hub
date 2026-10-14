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

    /**
     * 管理详情冻结价格契约：变更当前定价指针不得改变历史订单，供应价必须是尝试单价。
     * 设计：docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#322-订单详情金额与价格修订2026-10-14已评审通过。
     */
    @Test void adminDetailKeepsFrozenPricesAfterCurrentPricingChanges() {
        save(ProjectStandardAssetPayloadDecoderTest.huashengPayload(""));
        db.transaction().executeWithoutResult(tx -> {
            db.jdbc.update("""
                    INSERT INTO sc_buyer_sku_pricing_version
                    (id,pricing_id,version_no,platform_pricing_version_id,override_json,effective_config_json,
                    distribution_rule_json,sales_rule_json,service_fee_rule_json,hide_rule_json,tax_metadata_json,
                    market_price_cents,suggested_sale_price_cents,supply_price_cents,distribution_price_cents,
                    pricing_status,currency,published_at,created_at)
                    VALUES (301,401,1,501,'{}','{}','{}','{}','{}','{}','{}',3000,3000,100,2500,
                    'READY','CNY',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
                    (302,401,2,501,'{}','{}','{}','{}','{}','{}','{}',3000,3000,100,2900,
                    'READY','CNY',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                    """);
            db.jdbc.update("""
                    INSERT INTO sc_project_sku_pricing_version
                    (id,pricing_id,version_no,buyer_pricing_version_id,override_json,effective_config_json,
                    market_price_cents,suggested_sale_price_cents,supply_price_cents,distribution_price_cents,
                    pricing_status,currency,published_at,created_at)
                    VALUES (201,601,1,301,'{"distributionInherited":false}','{}',3000,3000,100,2250,
                    'READY','CNY',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
                    (202,601,2,302,'{"distributionInherited":true}','{}',3000,3000,100,2900,
                    'READY','CNY',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                    """);
            db.jdbc.update("""
                    INSERT INTO sc_buyer_sku_pricing
                    (id,buyer_product_id,current_version_id,pricing_status,created_at,updated_at)
                    VALUES (401,701,301,'READY',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                    """);
            db.jdbc.update("""
                    INSERT INTO sc_project_sku_pricing
                    (id,listing_id,current_version_id,pricing_status,created_at,updated_at)
                    VALUES (601,1,201,'READY',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                    """);
            db.jdbc.update("""
                    INSERT INTO sc_standard_pricing_quote
                    (id,quote_token,buyer_id,project_id,listing_id,platform_sku_id,platform_sku_code,
                    project_pricing_version_id,buyer_order_no,quantity,distribution_unit_price_cents,
                    sales_unit_price_cents,product_subtotal_cents,service_fee_cents,user_payable_amount_cents,
                    tax_metadata_json,status,created_at)
                    VALUES (101,'detail-price-test',2,3,1,1,'sku',201,'order',1,2250,3000,3000,500,3500,
                    '{}','CONSUMED',CURRENT_TIMESTAMP(3))
                    """);
            db.jdbc.update("""
                    UPDATE sc_supply_chain_order SET pricing_quote_id=101,pricing_version_id=201,
                    buyer_settlement_amount=2250,sales_unit_price_cents=3000,
                    product_subtotal_cents=3000,service_fee_cents=500,user_payable_amount_cents=3500 WHERE id=1
                    """);
            db.jdbc.update("""
                    UPDATE sc_b_order SET supplier_settlement_unit_price=1800,
                    expected_supplier_settlement_amount=1800 WHERE id=10
                    """);
            var original = json.valueToTree(admin.get("SC_1", "PLATFORM", 9001L));
            assertThat(original.path("distributorOrderNo").textValue()).isEqualTo("order");
            assertThat(original.path("projectPayAmount").longValue()).isEqualTo(2250L);
            assertThat(original.path("distributorPayAmount").longValue()).isEqualTo(2250L);
            var item = original.path("items").get(0);
            assertThat(item.path("supplyPrice").longValue()).isEqualTo(1800L);
            assertThat(item.path("baseDistPrice").longValue()).isEqualTo(2500L);
            assertThat(item.path("projectPrice").longValue()).isEqualTo(2250L);
            assertThat(item.path("distPrice").longValue()).isEqualTo(2250L);
            db.jdbc.update("UPDATE sc_buyer_sku_pricing SET current_version_id=302 WHERE id=401");
            db.jdbc.update("UPDATE sc_project_sku_pricing SET current_version_id=202 WHERE id=601");
            var replay = json.valueToTree(admin.get("SC_1", "PLATFORM", 9001L));
            assertThat(replay).isEqualTo(original);
            int audits = db.jdbc.queryForObject("SELECT COUNT(*) FROM sc_sensitive_access_audit", Integer.class);
            var supplier = json.valueToTree(admin.get("BO_10", "SUPPLIER", 9001L));
            assertThat(supplier.path("items").get(0).path("supplyPrice").longValue()).isEqualTo(1800L);
            assertThat(supplier.path("items").get(0).path("cardInfos")).isEmpty();
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sc_sensitive_access_audit", Integer.class))
                    .isEqualTo(audits);
            tx.setRollbackOnly();
        });
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
