package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** 真实 AES-GCM 的封闭 checkpoint 契约；设计见生产闭环详细设计 §4.2。 */
class HuashengCheckpointCodecTest {
    private static final String KEY_VERSION = "checkpoint-test-key-v1";
    private final EnvelopeCryptoPort crypto = new AesGcmEnvelopeCrypto(KEY_VERSION,
            Base64.getEncoder().encodeToString(new byte[32]), Base64.getEncoder().encodeToString(new byte[32]));
    private final HuashengCheckpointCodec codec = new HuashengCheckpointCodec(crypto);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void roundTripsCompleteFactsWithoutChangingRawMoneyOrOptionalValues() {
        var value = checkpoint();
        var stored = codec.encrypt(value);
        assertThat(stored.schemaVersion()).isEqualTo(1);
        assertThat(stored.keyVersion()).isEqualTo(KEY_VERSION);
        var decoded = codec.decrypt(stored, binding());
        assertThat(decoded.binding().operationNo()).isEqualTo("HS_OP_1");
        assertThat(decoded.placeParameters()).isEqualTo(value.placeParameters());
        assertThat(decoded.placeParametersDigest()).isEqualTo(value.placeParametersDigest());
        assertThat(decoded.orderSn()).isEqualTo("SUPPLIER_ORDER_SECRET");
        assertThat(decoded.observation()).usingRecursiveComparison().isEqualTo(value.observation());
        assertThat(decoded.observation().totalPriceRaw()).isEqualTo("09.9900");
        assertThat(decoded.observation().totalPriceMinorUnits()).isEqualTo(999);
        assertThat(decoded.progress()).isEqualTo(value.progress());
        assertThat(decoded.pendingAssetPayload()).containsExactly(value.pendingAssetPayload());
    }

    @Test
    void initialCheckpointDoesNotInventMoneyOrderOrSendFacts() {
        var value = new HuashengCheckpoint(binding(), place(), null, null,
                new HuashengCheckpoint.Progress(0, 0, 0, null, null, null), null);
        var decoded = codec.decrypt(codec.encrypt(value), binding());
        assertThat(decoded.orderSn()).isNull();
        assertThat(decoded.observation()).isNull();
        assertThat(decoded.progress().firstSendAt()).isNull();
        assertThat(decoded.progress().recoveryDeadlineAt()).isNull();
        assertThat(decoded.pendingAssetPayload()).isNull();
    }

    @Test
    void billnoOutOrderSnIsNotAssumedToBeBOrderNo() {
        var place = new HuashengCheckpoint.PlaceParameters("BILL_FROZEN_1", 123, 1, 300, null,
                "https://callback.example/huasheng/11");
        var value = new HuashengCheckpoint(binding(), place, null, null,
                new HuashengCheckpoint.Progress(0, 0, 0, null, null, null), null);
        var decoded = codec.decrypt(codec.encrypt(value), binding());
        assertThat(decoded.binding().bOrderNo()).isEqualTo("BORDER_1");
        assertThat(decoded.placeParameters().outOrderSn()).isEqualTo("BILL_FROZEN_1");
    }

    @Test
    void reencryptsForNextOperationInsteadOfCopyingOldCiphertext() {
        var original = codec.encrypt(checkpoint());
        var old = codec.decrypt(original, binding());
        var nextBinding = new HuashengCheckpoint.Binding(1, "BORDER_1", "HS_OP_2", 2,
                AdapterStepCode.CONFIRM_ORDER, 11, 22, binding().configDigest());
        var next = new HuashengCheckpoint(nextBinding, old.placeParameters(), old.orderSn(), old.observation(),
                old.progress(), old.pendingAssetPayload());
        assertThatThrownBy(() -> codec.decrypt(original, nextBinding)).isInstanceOf(IllegalArgumentException.class);
        var persisted = codec.encrypt(next);
        var restored = codec.decrypt(persisted, nextBinding);
        assertThat(restored.binding().sequenceNo()).isEqualTo(2);
        assertThat(restored.placeParametersDigest()).isEqualTo(old.placeParametersDigest());
        assertThat(restored.observation().totalPriceRaw()).isEqualTo("09.9900");
        assertThat(restored.progress()).isEqualTo(old.progress());
    }

    @Test
    void enforcesDepthAndStringLimitsButAllowsBoundedPendingAssetEvidence() throws Exception {
        var root = tree();
        ((ObjectNode) root.get("placeParameters")).put("outMobile", "s".repeat(16_385));
        assertThatThrownBy(() -> codec.decrypt(seal(json.writeValueAsBytes(root)), binding()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("华昇恢复检查点无效").hasNoCause();
        String deep = "[".repeat(33) + "0" + "]".repeat(33);
        assertThatThrownBy(() -> codec.decrypt(seal(deep.getBytes(StandardCharsets.UTF_8)), binding()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("华昇恢复检查点无效").hasNoCause();
        byte[] evidence = new byte[24_000]; Arrays.fill(evidence, (byte) 7);
        var value = new HuashengCheckpoint(binding(), place(), null, null,
                new HuashengCheckpoint.Progress(0, 0, 0, null, null, null), evidence);
        assertThat(codec.decrypt(codec.encrypt(value), binding()).pendingAssetPayload()).containsExactly(evidence);
        var tooLarge = new HuashengCheckpoint(binding(), place(), null, null, value.progress(), new byte[1_048_576]);
        assertThatThrownBy(() -> codec.encrypt(tooLarge)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("华昇恢复检查点无效").hasNoCause();
    }

    @Test
    void authenticatesEveryAadIdentityAndChecksNonAadBindingAndConfigDigest() {
        var b = binding();
        var stored = codec.encrypt(checkpoint());
        byte[] drift = b.configDigest(); drift[0] ^= 1;
        for (var moved : List.of(
                new HuashengCheckpoint.Binding(2, b.bOrderNo(), b.operationNo(), 1, b.step(), 11, 22, b.configDigest()),
                new HuashengCheckpoint.Binding(1, b.bOrderNo(), "OTHER_OP", 1, b.step(), 11, 22, b.configDigest()),
                new HuashengCheckpoint.Binding(1, b.bOrderNo(), b.operationNo(), 2, b.step(), 11, 22, b.configDigest()),
                new HuashengCheckpoint.Binding(1, b.bOrderNo(), b.operationNo(), 1, AdapterStepCode.CONFIRM_ORDER, 11, 22, b.configDigest()),
                new HuashengCheckpoint.Binding(1, b.bOrderNo(), b.operationNo(), 1, b.step(), 12, 22, b.configDigest()),
                new HuashengCheckpoint.Binding(1, b.bOrderNo(), b.operationNo(), 1, b.step(), 11, 23, b.configDigest()),
                new HuashengCheckpoint.Binding(1, "OTHER_BORDER", b.operationNo(), 1, b.step(), 11, 22, b.configDigest()),
                new HuashengCheckpoint.Binding(1, b.bOrderNo(), b.operationNo(), 1, b.step(), 11, 22, drift))) {
            assertThatThrownBy(() -> codec.decrypt(stored, moved)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("华昇恢复检查点无效").hasNoCause();
        }
    }

    @Test
    void persistedOriginalStepIsRequiredEvenWhenRecoveringViaQueryEndpoint() {
        var decoded = codec.decrypt(codec.encrypt(checkpoint()), binding());
        assertThat(decoded.binding().step()).isEqualTo(AdapterStepCode.PLACE_ORDER);
        var b = binding();
        var queryBinding = new HuashengCheckpoint.Binding(1, b.bOrderNo(), b.operationNo(), 1,
                AdapterStepCode.QUERY_ORDER, 11, 22, b.configDigest());
        assertThatThrownBy(() -> codec.decrypt(codec.encrypt(checkpoint()), queryBinding))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesSpecifiedUtf8AadAndCanonicalPlaceDigest() throws Exception {
        var stored = codec.encrypt(checkpoint());
        byte[] plaintext = crypto.decrypt(stored.ciphertext(), stored.keyVersion(), aad());
        try {
            var root = (ObjectNode) json.readTree(plaintext);
            String canonicalPlace = "{\"outOrderSn\":\"BORDER_1\",\"couponId\":123,\"num\":1,\"expireTime\":300,"
                    + "\"outMobile\":\"13800000000\",\"notifyUrl\":\"https://callback.example/huasheng/11\"}";
            assertThat(json.writeValueAsString(root.get("placeParameters"))).isEqualTo(canonicalPlace);
            String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalPlace.getBytes(StandardCharsets.UTF_8)));
            assertThat(root.get("placeParametersDigest").textValue()).isEqualTo(digest);
            assertThat(root.get("observation").get("totalPriceRaw").textValue()).isEqualTo("09.9900");
            assertThat(root.get("observation").get("refundStatus").isNull()).isTrue();
            assertThat(root.get("observation").has("isTake")).isFalse();
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }

    @Test
    void rejectsMissingGroupUnknownSchemaKeyAndCorruptCiphertext() {
        var stored = codec.encrypt(checkpoint());
        byte[] corrupted = stored.ciphertext(); corrupted[corrupted.length - 1] ^= 1;
        for (var invalid : List.of(
                new HuashengCheckpointCodec.Stored(null, null, null),
                new HuashengCheckpointCodec.Stored(null, KEY_VERSION, stored.ciphertext()),
                new HuashengCheckpointCodec.Stored(1, null, stored.ciphertext()),
                new HuashengCheckpointCodec.Stored(1, " ", stored.ciphertext()),
                new HuashengCheckpointCodec.Stored(1, KEY_VERSION, null),
                new HuashengCheckpointCodec.Stored(1, KEY_VERSION, new byte[0]),
                new HuashengCheckpointCodec.Stored(2, KEY_VERSION, stored.ciphertext()),
                new HuashengCheckpointCodec.Stored(1, "other-key", stored.ciphertext()),
                new HuashengCheckpointCodec.Stored(1, KEY_VERSION, corrupted))) {
            assertThatThrownBy(() -> codec.decrypt(invalid, binding())).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("华昇恢复检查点无效").hasNoCause();
        }
    }

    @Test
    void rejectsUnknownFieldsSchemaDriftMoneyDriftAndPlaceDigestDrift() throws Exception {
        ObjectNode original = tree();
        for (String mutation : List.of("schema", "rootExtra", "placeExtra", "bindingExtra", "money",
                "priceRaw", "digest", "mobile", "orderSn", "quantity", "float", "overflow", "enum", "negative", "deadline")) {
            var root = original.deepCopy();
            switch (mutation) {
                case "schema" -> root.put("schemaVersion", 2);
                case "rootExtra" -> root.put("token", "fixture-token-secret");
                case "placeExtra" -> ((ObjectNode) root.get("placeParameters")).put("privateKey", "fixture-key-secret");
                case "bindingExtra" -> ((ObjectNode) root.get("binding")).put("unknown", 1);
                case "money" -> ((ObjectNode) root.get("observation")).put("totalPriceMinorUnits", 998);
                case "priceRaw" -> ((ObjectNode) root.get("observation")).put("totalPriceRaw", "invalid-secret-money");
                case "digest" -> root.put("placeParametersDigest", "00".repeat(32));
                case "mobile" -> ((ObjectNode) root.get("placeParameters")).put("outMobile", "different-secret-mobile");
                case "orderSn" -> ((ObjectNode) root.get("observation")).put("orderSn", "conflicting-order-secret");
                case "quantity" -> ((ObjectNode) root.get("observation")).put("num", 2);
                case "float" -> ((ObjectNode) root.get("binding")).put("sequenceNo", 1.0);
                case "overflow" -> ((ObjectNode) root.get("binding")).put("bOrderId", new java.math.BigInteger("9223372036854775808"));
                case "enum" -> ((ObjectNode) root.get("binding")).put("step", "SEND_KM");
                case "negative" -> ((ObjectNode) root.get("progress")).put("queryCount", -1);
                case "deadline" -> ((ObjectNode) root.get("progress")).remove("firstSendAt");
                default -> throw new AssertionError(mutation);
            }
            assertThatThrownBy(() -> codec.decrypt(seal(json.writeValueAsBytes(root)), binding()))
                    .as(mutation).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("华昇恢复检查点无效").hasNoCause();
        }
    }

    @Test
    void rejectsDuplicateKeysTrailingDataAndOversizedPlaintextWithoutLeakingInput() throws Exception {
        String original = json.writeValueAsString(tree());
        for (String bad : List.of(original.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                original + " {}", "{\"secret\":", " ".repeat(1_048_577))) {
            assertThatThrownBy(() -> codec.decrypt(seal(bad.getBytes(StandardCharsets.UTF_8)), binding()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("华昇恢复检查点无效").hasNoCause();
        }
    }

    @Test
    void makesDefensiveCopiesForBindingPendingAssetAndStoredCiphertext() {
        byte[] digest = new byte[32];
        var b = new HuashengCheckpoint.Binding(1, "BORDER_1", "HS_OP_1", 1, AdapterStepCode.PLACE_ORDER, 11, 22, digest);
        digest[0] = 9; assertThat(b.configDigest()[0]).isZero();
        byte[] returnedDigest = b.configDigest(); returnedDigest[0] = 9;
        assertThat(b.configDigest()[0]).isZero();
        byte[] payload = "secret-card-payload".getBytes(StandardCharsets.UTF_8);
        var checkpoint = new HuashengCheckpoint(b, place(), null, null,
                new HuashengCheckpoint.Progress(0, 0, 0, null, null, null), payload);
        payload[0] = 0;
        byte[] returned = checkpoint.pendingAssetPayload(); returned[0] = 0;
        assertThat(new String(checkpoint.pendingAssetPayload(), StandardCharsets.UTF_8)).isEqualTo("secret-card-payload");
        var stored = codec.encrypt(checkpoint);
        byte[] source = stored.ciphertext();
        var copy = new HuashengCheckpointCodec.Stored(1, KEY_VERSION, source); source[0] ^= 1;
        byte[] output = copy.ciphertext(); output[0] ^= 1;
        assertThat(copy.ciphertext()).containsExactly(stored.ciphertext());
    }

    @Test
    void clearsTemporaryPlaintextOnSuccessfulAndFailedDecodeAndEncode() {
        var recording = new RecordingCrypto(crypto);
        var codec = new HuashengCheckpointCodec(recording);
        var stored = codec.encrypt(checkpoint());
        assertThat(recording.plaintext).containsOnly((byte) 0);
        codec.decrypt(stored, binding());
        assertThat(recording.plaintext).containsOnly((byte) 0);
        assertThatThrownBy(() -> codec.decrypt(seal("{\"secret\":".getBytes(StandardCharsets.UTF_8)), binding()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(recording.plaintext).containsOnly((byte) 0);
    }

    @Test
    void toStringIsRedactedAndAccidentalGenericSerializationIsDenied() {
        var value = checkpoint(); var stored = codec.encrypt(value);
        for (Object internal : List.of(value, value.binding(), value.placeParameters(), value.observation(),
                value.progress(), stored)) {
            assertThat(internal.toString()).doesNotContain("SUPPLIER_ORDER_SECRET", "13800000000", "09.9900",
                    "callback.example", "origin-trace-secret", "secret-card-payload");
            assertThatThrownBy(() -> json.writeValueAsString(internal)).hasMessageNotContaining("13800000000");
        }
    }

    @Test
    void observationOptionalRefundAndTakeFieldsRetainAllThreeStates() {
        for (HuashengField<HuashengRefundStatus> refund : List.<HuashengField<HuashengRefundStatus>>of(
                HuashengField.absent(), HuashengField.ofNull(), HuashengField.ofValue(HuashengRefundStatus.NONE))) {
            for (HuashengField<HuashengTakeStatus> take : List.<HuashengField<HuashengTakeStatus>>of(
                    HuashengField.absent(), HuashengField.ofNull(), HuashengField.ofValue(HuashengTakeStatus.N))) {
                var observation = new HuashengCheckpoint.Observation(HuashengEndpoint.QUERY, "SUPPLIER_ORDER_SECRET",
                        "BORDER_1", 1, "09.9900", 999, HuashengOrderStatus.WAIT, refund, take);
                var value = new HuashengCheckpoint(binding(), place(), "SUPPLIER_ORDER_SECRET", observation,
                        checkpoint().progress(), null);
                assertThat(codec.decrypt(codec.encrypt(value), binding()).observation())
                        .usingRecursiveComparison().isEqualTo(observation);
            }
        }
    }

    @Test
    void refusesInventedMoneyFromTakeAndInvalidProgressOrPlaceParameters() {
        assertThatThrownBy(() -> new HuashengCheckpoint.Observation(HuashengEndpoint.TAKE, "ORDER", "BORDER_1", 1,
                "9.99", 999, HuashengOrderStatus.SUCCESS, HuashengField.absent(), HuashengField.absent()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HuashengCheckpoint.Progress(-1, 0, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HuashengCheckpoint.PlaceParameters("BORDER_1", 123, 2, 300, null, "https://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistedMoneyInvariantAgreesWithProtocolParserWithoutRewritingCommunication() {
        for (String raw : List.of("0", "0.0049", "0.0050", "9.9999", "09.9900", "92233720368547758.07")) {
            var fact = (HuashengInternalAttempt.Parsed<HuashengPrice>) HuashengMoneyParser.parsePrice(raw);
            var observation = new HuashengCheckpoint.Observation(HuashengEndpoint.QUERY, "ORDER", "BORDER_1", 1,
                    raw, fact.value().minorUnits(), HuashengOrderStatus.WAIT, HuashengField.absent(), HuashengField.absent());
            assertThat(observation.totalPriceRaw()).isEqualTo(raw);
            assertThat(observation.totalPriceMinorUnits()).isEqualTo(fact.value().minorUnits());
        }
        for (String raw : List.of("-1", "+1", "1e2", " 9.99", "9.99 ", "1.12345", "NaN", "１", "9".repeat(33),
                "92233720368547758.08")) {
            assertThat(HuashengMoneyParser.parsePrice(raw)).isInstanceOf(HuashengInternalAttempt.Failed.class);
            assertThatThrownBy(() -> new HuashengCheckpoint.Observation(HuashengEndpoint.QUERY, "ORDER", "BORDER_1", 1,
                    raw, 999, HuashengOrderStatus.WAIT, HuashengField.absent(), HuashengField.absent()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("华昇恢复检查点无效").hasNoCause();
        }
    }

    private HuashengCheckpoint checkpoint() {
        var now = LocalDateTime.of(2026, 10, 9, 12, 0, 0, 123_000_000);
        return new HuashengCheckpoint(binding(), place(), "SUPPLIER_ORDER_SECRET",
                new HuashengCheckpoint.Observation(HuashengEndpoint.PLACE, "SUPPLIER_ORDER_SECRET", "BORDER_1", 1,
                        "09.9900", 999, HuashengOrderStatus.WAIT, HuashengField.ofNull(), HuashengField.absent()),
                new HuashengCheckpoint.Progress(2, 1, 0, now, now.plusMinutes(30), "origin-trace-secret"),
                "secret-card-payload".getBytes(StandardCharsets.UTF_8));
    }

    private HuashengCheckpoint.Binding binding() {
        return new HuashengCheckpoint.Binding(1, "BORDER_1", "HS_OP_1", 1, AdapterStepCode.PLACE_ORDER, 11, 22, new byte[32]);
    }

    private HuashengCheckpoint.PlaceParameters place() {
        return new HuashengCheckpoint.PlaceParameters("BORDER_1", 123, 1, 300, "13800000000", "https://callback.example/huasheng/11");
    }

    private byte[] aad() {
        return "HUA_SHENG_CHECKPOINT|1|1|HS_OP_1|1|PLACE_ORDER|11|22".getBytes(StandardCharsets.UTF_8);
    }

    private HuashengCheckpointCodec.Stored seal(byte[] plaintext) {
        var encrypted = crypto.encrypt(plaintext, aad());
        return new HuashengCheckpointCodec.Stored(1, encrypted.keyVersion(), encrypted.ciphertext());
    }

    private ObjectNode tree() throws Exception {
        var stored = codec.encrypt(checkpoint());
        byte[] plaintext = crypto.decrypt(stored.ciphertext(), stored.keyVersion(), aad());
        try { return (ObjectNode) json.readTree(plaintext); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    /** 只观察临时数组生命周期，仍委派真实 AES-GCM，不改变密文契约。 */
    private static final class RecordingCrypto implements EnvelopeCryptoPort {
        private final EnvelopeCryptoPort delegate;
        private byte[] plaintext;
        private RecordingCrypto(EnvelopeCryptoPort delegate) { this.delegate = delegate; }
        @Override public EncryptedPayload encrypt(byte[] plaintext, byte[] aad) {
            this.plaintext = plaintext; return delegate.encrypt(plaintext, aad);
        }
        @Override public byte[] decrypt(byte[] ciphertext, String keyVersion, byte[] aad) {
            plaintext = delegate.decrypt(ciphertext, keyVersion, aad); return plaintext;
        }
    }
}
