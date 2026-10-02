package com.aifuli.module.supplychain.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 签名客户端单测：与设计 §4 签名协议逐字段对拍，HMAC 期望值用 JDK crypto 独立重算避免同义反复。
 */
class ProjectAppAuthSignerTest {

    private static final String APP_ID = "app-signer-test";
    private static final String APP_KEY = "secret-key-for-signer-test";
    private static final String APP_KEY_SERIALIZATION_SENTINEL =
            "PROJECT_APP_KEY_MUST_NEVER_APPEAR_IN_SERIALIZED_OUTPUT";
    private static final String CHANNEL_PATH =
            "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder";
    private static final String ENTRY_PATH = "/rpc-api/supply-chain/instore/buyer-entry/home";
    private static final String CALLBACK_PATH =
            "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment";

    @Test
    void signProducesAllTenHeadersWithValidSignature() throws Exception {
        byte[] body = "{\"channelOrderId\":\"RT-1\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 3, null, "RT_MART"),
                "POST", CHANNEL_PATH, body, 1_700_000_000_000L, "nonce-fixed-1");

        assertThat(headers).containsOnlyKeys("appId", "keyVersion", "contractVersion", "operationCode",
                "timestamp", "nonce", "requestSha256", "signature", "buyerUserId", "channelCode");
        assertThat(headers.get("appId")).isEqualTo(APP_ID);
        assertThat(headers.get("keyVersion")).isEqualTo("3");
        assertThat(headers.get("contractVersion")).isEqualTo("1.0.0");
        assertThat(headers.get("operationCode")).isEqualTo("INSTORE_CREATE_PAYMENT_ORDER");
        assertThat(headers.get("timestamp")).isEqualTo("1700000000000");
        assertThat(headers.get("nonce")).isEqualTo("nonce-fixed-1");
        assertThat(headers.get("buyerUserId")).isEmpty();
        assertThat(headers.get("channelCode")).isEqualTo("RT_MART");
        assertThat(headers.get("requestSha256"))
                .isEqualTo(ProjectAppAuthSigner.sha256Hex(body));

        // 独立重算 HMAC 期望值
        String canonical = String.join("\n", APP_ID, "3", "1.0.0", "INSTORE_CREATE_PAYMENT_ORDER",
                "1700000000000", "nonce-fixed-1", headers.get("requestSha256"), "", "RT_MART");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(headers.get("signature")).isEqualTo(expected);
    }

    @Test
    void standardOperationsEmitEightHeadersAndSignEmptyScopeFields() throws Exception {
        for (String suffix : java.util.List.of("", "/query", "/assets/query")) {
            byte[] body = "{\"buyerOrderNo\":\"STD-1\"}".getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = ProjectAppAuthSigner.sign(
                    new ProjectAppAuthCredentials(APP_ID, APP_KEY, 3, null, null), "POST",
                    ProjectAuthOperations.STANDARD_ORDERS_PATH + suffix, body, 42L, "standard-nonce");
            assertThat(headers.keySet()).containsExactlyInAnyOrder("appId", "keyVersion", "contractVersion",
                    "operationCode", "timestamp", "nonce", "requestSha256", "signature");
            String canonical = String.join("\n", APP_ID, "3", "1.0.0", headers.get("operationCode"),
                    "42", "standard-nonce", headers.get("requestSha256"), "", "");
            assertThat(canonical.split("\n", -1)).hasSize(9);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(APP_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            assertThat(headers.get("signature").equals(expected)).isTrue();
            Map<String, String> changed = ProjectAppAuthSigner.sign(
                    new ProjectAppAuthCredentials(APP_ID, APP_KEY, 3, null, null), "POST",
                    ProjectAuthOperations.STANDARD_ORDERS_PATH + suffix,
                    "{ \"buyerOrderNo\":\"STD-1\"}".getBytes(StandardCharsets.UTF_8), 42L, "standard-nonce");
            assertThat(headers.get("signature").equals(changed.get("signature"))).isFalse();
        }
    }

    @Test
    void standardOperationsRejectAnyScopeDeclarationAndOldCallbacksKeepTenHeaders() {
        for (String scope : java.util.List.of("scope", "", " ")) {
            for (String suffix : java.util.List.of("", "/query", "/assets/query")) {
                assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                        new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, scope, null), "POST",
                        ProjectAuthOperations.STANDARD_ORDERS_PATH + suffix, null))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                        new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, scope), "POST",
                        ProjectAuthOperations.STANDARD_ORDERS_PATH + suffix, null))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThat(ProjectAppAuthSigner.sign(new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, null),
                "POST", CALLBACK_PATH, null)).hasSize(10);
    }

    @Test
    void signIsDeterministicWithExplicitTimestampAndNonce() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> first = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, "u-1", null),
                "POST", ENTRY_PATH, body, 42L, "n-1");
        Map<String, String> second = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, "u-1", null),
                "POST", ENTRY_PATH, body, 42L, "n-1");
        assertThat(first).isEqualTo(second);
        assertThat(first.get("operationCode")).isEqualTo("INSTORE_HOME_ENTRY");
        assertThat(first.get("buyerUserId")).isEqualTo("u-1");
        assertThat(first.get("channelCode")).isEmpty();
    }

    @Test
    void sha256HexMatchesKnownVectors() {
        assertThat(ProjectAppAuthSigner.sha256Hex(null))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ProjectAppAuthSigner.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8));
            assertThat(ProjectAppAuthSigner.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo(HexFormat.of().formatHex(digest));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void canonicalStringJoinsNineFieldsWithNewlines() {
        String canonical = ProjectAppAuthSigner.canonicalString("a", "1", "1.0.0", "OP", "2", "n",
                "sha", "u", "c");
        assertThat(canonical).isEqualTo("a\n1\n1.0.0\nOP\n2\nn\nsha\nu\nc");
    }

    @Test
    void signRejectsUnregisteredOperation() {
        assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", "/rpc-api/supply-chain/instore/channel-command/notRegistered", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未登记的受保护操作");
    }

    @Test
    void signRejectsMissingScopeForChannelOperation() {
        assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, null),
                "POST", CHANNEL_PATH, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelCode");
    }

    @Test
    void signRejectsBuyerUserIdOnChannelOperation() {
        assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, "u-1", "RT_MART"),
                "POST", CHANNEL_PATH, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyerUserId");
    }

    @Test
    void signRejectsMissingScopeForBuyerEntryOperation() {
        assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", ENTRY_PATH, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyerUserId");
    }

    @Test
    void signRejectsChannelCodeOnBuyerEntryOperation() {
        assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, "u-1", "RT_MART"),
                "POST", ENTRY_PATH, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelCode");
    }

    @Test
    void signRejectsScopeFieldsOnCallbackOperation() {
        assertThatThrownBy(() -> ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"),
                "POST", CALLBACK_PATH, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许携带 scope 声明");
    }

    @Test
    void randomNonceIsBase64UrlAndUnique() {
        String first = ProjectAppAuthSigner.randomNonce();
        String second = ProjectAppAuthSigner.randomNonce();
        assertThat(first).matches("[A-Za-z0-9_-]+");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void fourArgSignOverloadGeneratesTimestampAndNonce() {
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 2, null, "RT_MART"),
                "POST", CHANNEL_PATH, null);
        assertThat(headers).hasSize(10);
        assertThat(Long.parseLong(headers.get("timestamp"))).isPositive();
        assertThat(headers.get("nonce")).isNotBlank();
    }

    @Test
    void credentialsRejectBlankIdentityAndNonPositiveKeyVersion() {
        assertThatThrownBy(() -> new ProjectAppAuthCredentials(" ", APP_KEY, 1, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId");
        assertThatThrownBy(() -> new ProjectAppAuthCredentials(APP_ID, null, 1, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appKey");
        assertThatThrownBy(() -> new ProjectAppAuthCredentials(APP_ID, APP_KEY, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyVersion");
    }

    @Test
    void credentialsKeepAppKeyReadableButExcludeItFromDefaultJacksonSerialization() throws Exception {
        ProjectAppAuthCredentials credentials = new ProjectAppAuthCredentials(
                APP_ID, APP_KEY_SERIALIZATION_SENTINEL, 1, null, "RT_MART");

        String json = new ObjectMapper().writeValueAsString(credentials);
        JsonNode jsonNode = new ObjectMapper().readTree(json);

        assertThat(credentials.appKey()).isEqualTo(APP_KEY_SERIALIZATION_SENTINEL);
        assertThat(jsonNode.has("appKey")).isFalse();
        assertThat(json).doesNotContain(APP_KEY_SERIALIZATION_SENTINEL);
    }
}
