package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialSlotIdentity;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepCheckpoint.*;
import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SingleStepCheckpointCodecTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EnvelopeCryptoPort crypto = new AesGcmEnvelopeCrypto("v1",
            Base64.getEncoder().encodeToString(new byte[32]), Base64.getEncoder().encodeToString(new byte[32]));
    private final SingleStepCheckpointCodec codec = new SingleStepCheckpointCodec(crypto);

    @Test void encryptedRoundTripBindsOriginalSendAndRetainsApprovedRelativeContract() throws Exception {
        var checkpoint = checkpoint();
        var stored = codec.encrypt(checkpoint);
        assertThat(new String(stored.ciphertext(), StandardCharsets.UTF_8)).doesNotContain("merchant-fixture", "SKU-old");
        assertThat(codec.decrypt(stored, checkpoint.binding())).isEqualTo(checkpoint);
        assertThat(checkpoint.recoveryContract().firstQueryDelaySeconds()).isEqualTo(180);
        assertThat(checkpoint.recoveryContract().timeouts().executionLeaseMs()).isEqualTo(60_000);
        assertThat(new String(codec.encodePlaintext(checkpoint), StandardCharsets.UTF_8))
                .doesNotContain("recoveryDeadlineAt", "firstCreateAt", "executionLeaseUntil", "13800138000");
        assertThat(checkpoint.toString()).doesNotContain("SKU-old", "callback");
    }

    @Test void independentCanonicalVectorIsStableAndContentChangesEvenWithSameVersionIds() throws Exception {
        var checkpoint = checkpoint();
        // 手写规范化字段顺序与类型，独立于生产编码器；不是让被测函数生成自己的期望。
        String vector = """
                {"business":{"billno":"202610170000000000000001","callbackUrl":"https://platform.invalid/callback/7","currency":"CNY","externalSkuCode":"SKU-old","protectedTargetReference":{"requestId":11,"requestNo":"FR-original","targetDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","targetKeyVersion":"v1","targetSchemaVersion":1},"quantity":1,"settlementUnitPriceMinorUnits":884,"targetSchemaCode":"DIRECT_RECHARGE_TARGET"},"configuration":{"baseUrl":"https://supplier.invalid","merchantAccount":"merchant-fixture","versionId":7},"credentialIdentity":{"items":[],"setId":null,"slot":{"activeSecretVersion":"01","providerType":"ENV","slotId":9}},"recoveryContract":{"connectTimeoutMs":10000,"deadlinePolicyCode":"FIRST_CREATE_DAY_PLUS_TWO_MIDNIGHT","deadlineZoneId":"Asia/Shanghai","errorBackoffSeconds":600,"executionLeaseMs":60000,"firstQueryDelaySeconds":180,"maxQueryClaims":1000,"notFoundConsecutiveThreshold":3,"notFoundIntervalSeconds":180,"policyCode":"SINGLE_STEP_MOBILE_V1","processingIntervalSeconds":600,"readWriteTimeoutMs":20000,"timeoutFirstQueryDelaySeconds":180,"totalCallTimeoutMs":30000}}
                """.strip();
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(vector.getBytes(StandardCharsets.UTF_8)));
        assertThat(digest("{\"versionId\":7,\"merchantAccount\":\"merchant-fixture\",\"baseUrl\":\"https://supplier.invalid\"}", checkpoint)).isEqualTo(expected);
        assertThat(digest("{\"baseUrl\":\"https://supplier.invalid\",\"merchantAccount\":\"merchant-fixture\",\"versionId\":7}", checkpoint)).isEqualTo(expected);
        assertThat(digest("{\"baseUrl\":\"https://changed.invalid\",\"merchantAccount\":\"merchant-fixture\",\"versionId\":7}", checkpoint)).isNotEqualTo(expected);
        var price = new Business(checkpoint.business().billno(), "SKU-old", 1, "DIRECT_RECHARGE_TARGET",
                checkpoint.business().protectedTargetReference(), checkpoint.business().callbackUrl(), 999, "CNY");
        assertThat(SingleStepCheckpointCodec.contentDigest(json.readTree("{\"baseUrl\":\"https://supplier.invalid\",\"merchantAccount\":\"merchant-fixture\",\"versionId\":7}"),
                price, checkpoint.credentialIdentity(), checkpoint.recoveryContract())).isNotEqualTo(expected);
    }

    @Test void credentialSetItemOrderDoesNotChangeOriginalDigestAndVersionsStayIntegers() throws Exception {
        var original = checkpoint();
        var merchant = new com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialItemIdentity(31, "merchantKey", "ENV", 1);
        var privateKey = new com.aifuli.module.supplychain.border.fulfillment.domain.FrozenCredentialItemIdentity(12, "privateKey", "ENV", 2);
        var first = new CredentialIdentity(null, 41L, List.of(privateKey, merchant));
        var second = new CredentialIdentity(null, 41L, List.of(merchant, privateKey));
        var checkpoint = new SingleStepCheckpoint(1, original.binding(), original.business(), first, original.recoveryContract());
        assertThat(codec.decrypt(codec.encrypt(checkpoint), original.binding()).credentialIdentity()).isEqualTo(second);
        var config = json.readTree("{\"versionId\":7}");
        assertThat(SingleStepCheckpointCodec.contentDigest(config, original.business(), first, original.recoveryContract()))
                .isEqualTo(SingleStepCheckpointCodec.contentDigest(config, original.business(), second, original.recoveryContract()));
        var encoded = json.readTree(codec.encodePlaintext(checkpoint)).path("credentialIdentity").path("items");
        assertThat(encoded.get(0).path("itemKey").textValue()).isEqualTo("merchantKey");
        assertThat(encoded.get(1).path("activeSecretVersion").isIntegralNumber()).isTrue();
        assertThat(encoded.get(1).path("activeSecretVersion").intValue()).isEqualTo(2);
        assertThat(SingleStepCheckpointCodec.contentDigest(config, original.business(), first, RecoveryContract.initial(600)))
                .isNotEqualTo(SingleStepCheckpointCodec.contentDigest(config, original.business(), first, RecoveryContract.initial(180)));
    }

    @ParameterizedTest @ValueSource(strings = {"operation", "supplier", "connector", "offer", "digest", "step"})
    void ciphertextCannotMoveToAnotherOriginalBinding(String field) throws Exception {
        var checkpoint = checkpoint();
        var original = checkpoint.binding();
        var other = new Binding(field.equals("operation") ? "OP-other" : original.operationNo(), original.bOrderId(),
                original.adapterType(), field.equals("step") ? AdapterStepCode.QUERY_ORDER : original.adapterStepCode(),
                field.equals("supplier") ? 99 : original.supplierId(), field.equals("connector") ? 99 : original.connectorVersionId(),
                field.equals("offer") ? 99 : original.offerVersionId(), field.equals("digest") ? "b".repeat(64) : original.configDigest());
        assertThatThrownBy(() -> codec.decrypt(codec.encrypt(checkpoint), other)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"unknown", "duplicate", "trailing", "numericString", "float", "null", "missing", "schema", "depth", "size"})
    void authenticatedButMalformedJsonFailsClosedWithoutLeakingParserDiagnostics(String mutation) throws Exception {
        var checkpoint = checkpoint();
        String valid = new String(codec.encodePlaintext(checkpoint), StandardCharsets.UTF_8);
        String malformed = switch (mutation) {
            case "unknown" -> valid.replaceFirst("\\{", "{\"secret-fixture\":1,");
            case "duplicate" -> valid.replaceFirst("\\{", "{\"schemaVersion\":1,");
            case "trailing" -> valid + " {}";
            case "numericString" -> valid.replace("\"quantity\":1", "\"quantity\":\"1\"");
            case "float" -> valid.replace("\"quantity\":1", "\"quantity\":1.0");
            case "null" -> valid.replace("\"quantity\":1", "\"quantity\":null");
            case "missing" -> valid.replace("\"quantity\":1,", "");
            case "schema" -> valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
            case "depth" -> "[".repeat(14) + valid + "]".repeat(14);
            default -> valid + " ".repeat(65_536);
        };
        var attack = new EnvelopeCryptoPort() {
            public EncryptedPayload encrypt(byte[] plaintext, byte[] aad) { throw new UnsupportedOperationException(); }
            public byte[] decrypt(byte[] ciphertext, String version, byte[] aad) { return malformed.getBytes(StandardCharsets.UTF_8); }
        };
        assertThatThrownBy(() -> new SingleStepCheckpointCodec(attack).decrypt(new SingleStepCheckpointCodec.Stored(1, "v1", new byte[32]), checkpoint.binding()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("单步骤检查点无效").hasNoCause();
    }

    @Test void temporaryPlaintextIsWipedAfterEncryptionAndRejectedDecryption() throws Exception {
        var checkpoint = checkpoint();
        byte[][] owned = new byte[2][];
        var observing = new EnvelopeCryptoPort() {
            public EncryptedPayload encrypt(byte[] plaintext, byte[] aad) { owned[0] = plaintext; return new EncryptedPayload(new byte[32], "v1"); }
            public byte[] decrypt(byte[] ciphertext, String version, byte[] aad) { owned[1] = "{\"secret-fixture\":\"not-logged\"}".getBytes(StandardCharsets.UTF_8); return owned[1]; }
        };
        var observingCodec = new SingleStepCheckpointCodec(observing);
        var stored = observingCodec.encrypt(checkpoint);
        assertThat(owned[0]).containsOnly((byte) 0);
        assertThatThrownBy(() -> observingCodec.decrypt(stored, checkpoint.binding())).hasMessage("单步骤检查点无效");
        assertThat(owned[1]).containsOnly((byte) 0);
    }

    @Test void authenticatedRuntimeRoundTripRetainsFrozenProjectionAndCannotDowngradeSchema() throws Exception {
        var frozen = checkpoint();
        var progress = new com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepRuntimeCheckpoint.Progress(
                com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepAction.QUERY, 7, true, 2,
                com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepRuntimeCheckpoint.Fact.NOT_FOUND,
                java.time.LocalDateTime.of(2026, 10, 18, 23, 59, 12, 345_000_000));
        var runtime = new com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepRuntimeCheckpoint(2, frozen, progress);
        var stored = codec.encryptRuntime(runtime);
        assertThat(stored.schemaVersion()).isEqualTo(2);
        assertThat(codec.decryptRuntime(stored, frozen.binding())).isEqualTo(runtime);
        assertThat(codec.decrypt(stored, frozen.binding())).isEqualTo(frozen);
        assertThat(codec.encodePlaintext(codec.decrypt(stored, frozen.binding()))).isEqualTo(codec.encodePlaintext(frozen));
        assertThatThrownBy(() -> codec.decrypt(new SingleStepCheckpointCodec.Stored(1, stored.keyVersion(), stored.ciphertext()), frozen.binding()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("单步骤检查点无效");
        assertThat(codec.decryptRuntime(codec.encrypt(frozen), frozen.binding()).progress().trustedExists()).isFalse();
        assertThat(codec.decryptRuntime(codec.encrypt(frozen), frozen.binding()).progress().consecutiveNotFound()).isZero();
    }

    private String digest(String configuration, SingleStepCheckpoint checkpoint) throws Exception {
        return SingleStepCheckpointCodec.contentDigest(json.readTree(configuration), checkpoint.business(), checkpoint.credentialIdentity(), checkpoint.recoveryContract());
    }
    private SingleStepCheckpoint checkpoint() throws Exception {
        var business = new Business("202610170000000000000001", "SKU-old", 1, "DIRECT_RECHARGE_TARGET",
                new TargetReference(11, "FR-original", 1, "a".repeat(64), "v1"), "https://platform.invalid/callback/7", 884, "CNY");
        var credentials = new CredentialIdentity(new FrozenCredentialSlotIdentity(9, "ENV", "01"), null, List.of());
        var recovery = RecoveryContract.initial(180);
        return new SingleStepCheckpoint(1, new Binding("OP-original", 13, "TEST_SINGLE_STEP", AdapterStepCode.SEND,
                17, 7, 5, "c".repeat(64)), business, credentials, recovery);
    }
}
