package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/** A parser真实卡片到封闭payload，不反射构造协议DTO；生产闭环设计 §7.1。 */
class HuashengType201AssetCodecTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HuashengType201AssetCodec codec = new HuashengType201AssetCodec();

    @Test
    void projectsType202SingleCredentialWithoutChangingSupplierFields() throws Exception {
        for (String fields : List.of("\"code\":\" C202 \",\"pass\":\"\"", "\"code\":null,\"pass\":\" C202 \"",
                "\"pass\":\" C202 \"", "\"code\":\" C202 \",\"pass\":\"OTHER\"")) {
            var fact = codec.toFact(card("{\"type\":202," + fields + ",\"validTime\":null}"));
            assertThat(fact.cardNo()).isEqualTo(" C202 ");
            assertThat(fact.cardSecret()).isEqualTo(" C202 ");
            var source = json.readTree(codec.validate(fact)).get("supplierPayload");
            assertThat(source).isEqualTo(json.readTree("{\"adapterType\":\"HUA_SHENG_ORDER_V1\",\"type\":202,"
                    + fields + ",\"validTime\":null}"));
            var stored = codec.readStoredPayload(fact.protectedPayload(), 1, "CARD_CODE_SECRET", null, null, null);
            assertThat(stored.cardCode()).isEqualTo(" C202 ");
            assertThat(stored.cardSecret()).isEqualTo(" C202 ");
            assertThat(new String(codec.fingerprintInput(7, fact), StandardCharsets.UTF_8))
                    .isEqualTo("{\"supplierId\":7,\"type\":202,\"code\":\" C202 \",\"pass\":\" C202 \"}");
        }
        var fact = codec.toFact(card("{\"type\":202,\"code\":\"C202\"}"));
        var forged = (ObjectNode) json.readTree(fact.protectedPayload());
        forged.put("cardSecret", "OTHER");
        assertThatThrownBy(() -> codec.validate(withPayload(fact, json.writeValueAsBytes(forged))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesWhitespaceAndAllValidTimeStatesWithoutInventingDates() throws Exception {
        for (String suffix : List.of("", ",\"validTime\":null", ",\"validTime\":\"\"", ",\"validTime\":\" 长期有效 \"")) {
            var fact = codec.toFact(card("{\"type\":201,\"code\":\" CODE_SECRET \",\"pass\":\" PASS_SECRET \"" + suffix + "}"));
            assertThat(fact.cardNo()).isEqualTo(" CODE_SECRET ");
            assertThat(fact.cardSecret()).isEqualTo(" PASS_SECRET ");
            assertThat(fact.externalAssetId()).isNull();
            assertThat(fact.validFrom()).isNull(); assertThat(fact.validUntil()).isNull();
            var root = json.readTree(fact.protectedPayload());
            assertThat(root.get("cardCode").textValue()).isEqualTo(fact.cardNo());
            assertThat(root.get("cardSecret").textValue()).isEqualTo(fact.cardSecret());
            var supplier = root.get("supplierPayload");
            if (suffix.isEmpty()) assertThat(supplier.has("validTime")).isFalse();
            else if (suffix.contains("null")) assertThat(supplier.get("validTime").isNull()).isTrue();
            else assertThat(supplier.get("validTime").textValue()).isEqualTo(suffix.contains("长期") ? " 长期有效 " : "");
            assertThat(codec.validate(fact)).containsExactly(fact.protectedPayload());
        }
    }

    @Test
    void preservesAllProtocolFieldsButKeepsCardIdOnlyInsideProtectedPayload() throws Exception {
        var fact = codec.toFact(card("""
                {"type":201,"id":0,"code":" C ","pass":" P ","crc":null,"url":"","urlPass":" ","validTime":null}
                """));
        var supplier = json.readTree(fact.protectedPayload()).get("supplierPayload");
        assertThat(supplier.get("id").longValue()).isZero();
        assertThat(supplier.get("crc").isNull()).isTrue();
        assertThat(supplier.get("url").textValue()).isEmpty();
        assertThat(supplier.get("urlPass").textValue()).isEqualTo(" ");
        assertThat(fact.externalAssetId()).isNull();
        for (String id : List.of("", ",\"id\":null", ",\"id\":9223372036854775807")) {
            var candidate = codec.toFact(card("{\"type\":201,\"code\":\"C\",\"pass\":\"P\"" + id + "}"));
            assertThat(codec.validate(candidate)).containsExactly(candidate.protectedPayload());
        }
    }

    @Test
    void rejectsAllOtherCardTypesEvenWhenProtocolParserAcceptsThem() {
        for (String fields : List.of("\"type\":203,\"code\":\"C\",\"pass\":\"P\",\"crc\":\"R\"",
                "\"type\":301,\"url\":\"U\"", "\"type\":302,\"url\":\"U\",\"urlPass\":\"P\"",
                "\"type\":303,\"code\":\"C\",\"url\":\"U\",\"urlPass\":\"P\"", "\"type\":304,\"code\":\"C\",\"url\":\"U\"")) {
            assertThatThrownBy(() -> codec.toFact(card("{" + fields + "}")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("华昇type201资产无效").hasNoCause();
        }
        for (String field : List.of("crc", "url", "urlPass")) {
            assertThatThrownBy(() -> codec.toFact(card("{\"type\":201,\"code\":\"C\",\"pass\":\"P\",\""
                    + field + "\":\"OTHER_SECRET\"}"))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void validatesSchemaAndBothSetsOfCredentialsRatherThanTrustingBuilderPayload() throws Exception {
        var fact = codec.toFact(card("{\"type\":201,\"code\":\" C \",\"pass\":\" P \"}"));
        for (String mutation : List.of("schema", "type", "adapter", "code", "pass", "extra", "supplierExtra", "id", "float", "crc")) {
            var root = (ObjectNode) json.readTree(fact.protectedPayload());
            var supplier = (ObjectNode) root.get("supplierPayload");
            switch (mutation) {
                case "schema" -> root.put("payloadSchemaVersion", 2);
                case "type" -> root.put("assetType", "VOUCHER_CODE");
                case "adapter" -> supplier.put("adapterType", "OTHER");
                case "code" -> supplier.put("code", "DIFFERENT_SECRET");
                case "pass" -> root.put("cardSecret", "DIFFERENT_SECRET");
                case "extra" -> root.put("token", "TOKEN_SECRET");
                case "supplierExtra" -> supplier.put("unknown", "SECRET");
                case "id" -> supplier.put("id", -1);
                case "float" -> supplier.put("type", 201.0);
                case "crc" -> supplier.put("crc", "NOT_EMPTY_SECRET");
                default -> throw new AssertionError();
            }
            byte[] bad = json.writeValueAsBytes(root);
            assertThatThrownBy(() -> codec.validate(withPayload(fact, bad))).as(mutation)
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("华昇type201资产无效").hasNoCause();
        }
        String text = new String(fact.protectedPayload(), StandardCharsets.UTF_8);
        for (String bad : List.of(text + " {}", text.replace("\"payloadSchemaVersion\":1", "\"payloadSchemaVersion\":1,\"payloadSchemaVersion\":1"),
                " ".repeat(1_048_577))) {
            assertThatThrownBy(() -> codec.validate(withPayload(fact, bad.getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("华昇type201资产无效").hasNoCause();
        }
    }

    @Test
    void fingerprintUsesUnambiguousCanonicalCredentialsAndIgnoresIdAndValidTime() {
        var first = codec.toFact(card("{\"type\":201,\"id\":9,\"code\":\"a:b\",\"pass\":\"c\",\"validTime\":null}"));
        var metadataChanged = codec.toFact(card("{\"type\":201,\"id\":10,\"code\":\"a:b\",\"pass\":\"c\",\"validTime\":\"长期\"}"));
        var colonCollision = codec.toFact(card("{\"type\":201,\"code\":\"a\",\"pass\":\"b:c\"}"));
        assertThat(new String(codec.fingerprintInput(7, first), StandardCharsets.UTF_8))
                .isEqualTo("{\"supplierId\":7,\"type\":201,\"code\":\"a:b\",\"pass\":\"c\"}");
        assertThat(codec.fingerprintInput(7, first)).containsExactly(codec.fingerprintInput(7, metadataChanged));
        assertThat(codec.fingerprintInput(7, first)).isNotEqualTo(codec.fingerprintInput(7, colonCollision));
        assertThat(codec.fingerprintInput(7, first)).isNotEqualTo(codec.fingerprintInput(8, first));
    }

    @Test
    void defensiveCopiesAndRedactedToStringDoNotAlterLegacyTrimBehavior() {
        var fact = codec.toFact(card("{\"type\":201,\"code\":\" C_SECRET \",\"pass\":\" P_SECRET \"}"));
        byte[] payload = fact.protectedPayload();
        var copied = withPayload(fact, payload); payload[0] = 0;
        byte[] returned = copied.protectedPayload(); returned[0] = 0;
        assertThat(copied.protectedPayload()).containsExactly(fact.protectedPayload());
        assertThat(fact.toString()).doesNotContain("C_SECRET", "P_SECRET", "supplierPayload");
        assertThatThrownBy(() -> json.writeValueAsString(fact)).hasMessageNotContaining("C_SECRET");
        var legacy = new OperationAssetFact(0, null, " id ", " C ", " P ", null, null);
        assertThat(legacy.sequenceNo()).isEqualTo(1);
        assertThat(legacy.cardNo()).isEqualTo("C"); assertThat(legacy.cardSecret()).isEqualTo("P");
        assertThat(legacy.externalAssetId()).isEqualTo("id");
        assertThat(legacy.protectedPayload()).isNull();
    }

    @Test
    void builderDiagnosticsAlsoCannotExposeSecrets() {
        var builder = OperationAssetFact.builder().cardNo("CODE_SECRET").cardSecret("PASS_SECRET")
                .protectedPayload("PAYLOAD_SECRET".getBytes(StandardCharsets.UTF_8));
        assertThat(builder.toString()).doesNotContain("CODE_SECRET", "PASS_SECRET", "PAYLOAD_SECRET", "[80,");
    }

    @Test
    void optionalProtocolFieldsRemainAbsentNullAndOriginalBlankIndependently() throws Exception {
        for (String key : List.of("crc", "url", "urlPass", "validTime")) {
            for (String v : List.of("null", "\"\"", "\" \"")) {
                var fact = codec.toFact(card("{\"type\":201,\"code\":\"C\",\"pass\":\"P\",\"" + key + "\":" + v + "}"));
                var node = json.readTree(codec.validate(fact)).get("supplierPayload");
                assertThat(node.get(key)).isEqualTo(json.readTree(v));
                for (String other : List.of("crc", "url", "urlPass", "validTime")) {
                    if (!key.equals(other)) assertThat(node.has(other)).isFalse();
                }
            }
        }
    }

    @Test
    void builderCannotChangeSequenceTypeExternalIdDatesOrStandardIdentityOfProtectedFact() {
        var fact = codec.toFact(card("{\"type\":201,\"code\":\" C \",\"pass\":\" P \"}"));
        for (String mutation : List.of("sequence", "type", "id", "from", "until", "code", "pass")) {
            var builder = OperationAssetFact.builder().sequenceNo(1).assetType("CARD_CODE_SECRET")
                    .cardNo(fact.cardNo()).cardSecret(fact.cardSecret()).protectedPayload(fact.protectedPayload());
            switch (mutation) {
                case "sequence" -> builder.sequenceNo(0);
                case "type" -> builder.assetType("VOUCHER_CODE");
                case "id" -> builder.externalAssetId("9");
                case "from" -> builder.validFrom(java.time.LocalDateTime.of(2026, 10, 9, 0, 0));
                case "until" -> builder.validUntil(java.time.LocalDateTime.of(2026, 10, 9, 0, 0));
                case "code" -> builder.cardNo("C");
                case "pass" -> builder.cardSecret("P");
                default -> throw new AssertionError();
            }
            assertThatThrownBy(() -> codec.validate(builder.build())).as(mutation)
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("华昇type201资产无效").hasNoCause();
        }
        assertThatThrownBy(() -> codec.fingerprintInput(0, fact)).hasMessage("华昇type201资产无效");
    }

    @Test
    void rejectsOversizedDeepAndWrongTypeFieldValuesWithNoSensitiveParsingCause() throws Exception {
        var fact = codec.toFact(card("{\"type\":201,\"code\":\"C\",\"pass\":\"P\"}"));
        for (String bad : List.of("\"" + "S".repeat(16_385) + "\"", "[".repeat(33) + "null" + "]".repeat(33),
                "123", "{}", "true")) {
            String text = new String(fact.protectedPayload(), StandardCharsets.UTF_8)
                    .replace("\"pass\":\"P\"}", "\"pass\":\"P\",\"validTime\":" + bad + "}");
            assertThatThrownBy(() -> codec.validate(withPayload(fact, text.getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("华昇type201资产无效").hasNoCause();
        }
        var reordered = (ObjectNode) json.readTree(fact.protectedPayload());
        var newOrder = json.createObjectNode();
        newOrder.set("supplierPayload", reordered.get("supplierPayload"));
        newOrder.put("cardSecret", "P"); newOrder.put("cardCode", "C");
        newOrder.put("assetType", "CARD_CODE_SECRET"); newOrder.put("payloadSchemaVersion", 1);
        assertThat(codec.validate(withPayload(fact, json.writeValueAsBytes(newOrder)))).containsExactly(fact.protectedPayload());
        byte[] decrypted = json.writeValueAsBytes(newOrder);
        byte[] expectedBytes = decrypted.clone();
        byte[] canonical = codec.validateStoredPayload(fact, decrypted);
        canonical[0] = 0;
        assertThat(decrypted).containsExactly(expectedBytes);
        assertThat(codec.validateStoredPayload(fact, decrypted)).containsExactly(fact.protectedPayload());
    }

    static HuashengProtocolCard card(String cardJson) {
        var attempt = HuashengResponseParser.parseTake("{\"code\":0,\"data\":{\"orderSn\":\"HS_ORDER\",\"num\":1,\"list\":["
                + cardJson + "]}}", new HuashengExpectedOrder("BILL_1", Optional.of("HS_ORDER"), 1));
        assertThat(attempt).isInstanceOf(HuashengInternalAttempt.Parsed.class);
        var fact = ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengTakeResponse>>) attempt).value();
        return ((HuashengParseFact.Data<HuashengTakeResponse>) fact).data().list().getFirst();
    }

    private static OperationAssetFact withPayload(OperationAssetFact fact, byte[] payload) {
        return OperationAssetFact.builder().sequenceNo(fact.sequenceNo()).assetType(fact.assetType())
                .cardNo(fact.cardNo()).cardSecret(fact.cardSecret()).protectedPayload(payload).build();
    }
}
