package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.supplieradapter.huasheng.HuashengCrypto;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HuashengCallbackParserTest {

    private static final String APP_ID = "1234567890123456"; // 16 bytes
    private static final String APP_SECRET = "abcdef0123456789abcdef0123456789"; // 32 bytes AES-256
    private static PrivateKey privateKey;
    private static PublicKey publicKey;

    @BeforeAll
    static void setupKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        privateKey = kp.getPrivate();
        publicKey = kp.getPublic();
    }

    private String encrypt(String plain) {
        return HuashengCrypto.encryptBodyHex(plain, APP_SECRET, APP_ID);
    }

    private String sign(String cipherHex) {
        return HuashengCrypto.signBodyHex(cipherHex, privateKey);
    }

    @Test
    void malformedDecryptedValuesNeverAppearInExceptionMessageOrCause() {
        for (String field : new String[]{"totalPrice", "status"}) {
            String marker = "CARD_SECRET_SENTINEL_" + field;
            String plaintext = validSuccessPlainJson(null).replace(field.equals("status") ? "SUCCESS" : "10.00", marker);
            String cipher = encrypt(plaintext);
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> HuashengCallbackParser.parse(
                    cipher.getBytes(StandardCharsets.UTF_8), APP_ID, sign(cipher), APP_ID, publicKey, APP_SECRET));
            assertThat(failure).isInstanceOf(HuashengCallbackParseException.class);
            assertThat(failure.getMessage()).doesNotContain(marker);
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                assertThat(String.valueOf(cause.getMessage())).doesNotContain(marker);
            }
        }
    }

    private String validSuccessPlainJson(String validTimeVal) {
        String vt = validTimeVal != null ? ",\"validTime\":\"" + validTimeVal + "\"" : "";
        return "{\n" +
                "  \"timestamp\": 1700000000,\n" +
                "  \"nonce\": \"nonce_123456789\",\n" +
                "  \"orderSn\": \"HS_ORDER_999\",\n" +
                "  \"outOrderSn\": \"OUT_ORDER_888\",\n" +
                "  \"num\": 1,\n" +
                "  \"totalPrice\": \"10.00\",\n" +
                "  \"status\": \"SUCCESS\",\n" +
                "  \"list\": [\n" +
                "    {\n" +
                "      \"type\": 201,\n" +
                "      \"id\": 1001,\n" +
                "      \"code\": \"CARD_CODE_001\",\n" +
                "      \"pass\": \"CARD_PASS_002\"" + vt + "\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    @Test
    @DisplayName("有界读取：超过 2MiB 的原 hex 密文在验签/AES 前被拒绝")
    void rejectsBodyExceeding2MiBBeforeCrypto() {
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        Arrays.fill(oversized, (byte) 'a');

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                new ByteArrayInputStream(oversized),
                APP_ID,
                "dummySign",
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("2MiB");

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                oversized,
                APP_ID,
                "dummySign",
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("2MiB");
    }

    @Test
    @DisplayName("Header 限制：Appid 长度超过 16 字节被拒绝")
    void rejectsAppIdHeaderExceeding16Bytes() {
        String cipherHex = encrypt(validSuccessPlainJson("2026-12-31 23:59:59"));
        String sign = sign(cipherHex);
        String longAppId = "12345678901234567"; // 17 bytes

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                longAppId,
                sign,
                longAppId,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("Appid");
    }

    @Test
    @DisplayName("Header 限制：X-Sign 长度超过 4096 字节被拒绝")
    void rejectsXSignHeaderExceeding4096Bytes() {
        String cipherHex = encrypt(validSuccessPlainJson("2026-12-31 23:59:59"));
        String longSign = "a".repeat(4097);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                longSign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("X-Sign");
    }

    @Test
    @DisplayName("认证防护：Header Appid 与商户账号不匹配被拒绝")
    void rejectsAppIdMismatch() {
        String cipherHex = encrypt(validSuccessPlainJson("2026-12-31 23:59:59"));
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                "other_appid_123",
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("Appid");
    }

    @Test
    @DisplayName("验签防护：签名无效或篡改密文被拒绝")
    void rejectsSignatureMismatch() {
        String cipherHex = encrypt(validSuccessPlainJson("2026-12-31 23:59:59"));
        String sign = sign(cipherHex);
        String tamperedCipher = cipherHex.substring(0, cipherHex.length() - 2) + "00";

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                tamperedCipher.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("签名");
    }

    @Test
    @DisplayName("严格词法：解密后包含重复 key 被拒绝")
    void rejectsDuplicateKeysInJson() {
        String duplicateKeyJson = "{\n" +
                "  \"timestamp\": 1700000000,\n" +
                "  \"timestamp\": 1700000001,\n" +
                "  \"nonce\": \"nonce1\",\n" +
                "  \"orderSn\": \"HS_1\",\n" +
                "  \"outOrderSn\": \"OUT_1\",\n" +
                "  \"num\": 1,\n" +
                "  \"totalPrice\": \"10.00\",\n" +
                "  \"status\": \"SUCCESS\",\n" +
                "  \"list\": [{\"type\": 201, \"code\": \"C\", \"pass\": \"P\"}]\n" +
                "}";
        String cipherHex = encrypt(duplicateKeyJson);
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("严格词法：解密后包含尾随字符被拒绝")
    void rejectsTrailingTokensInJson() {
        String trailingJson = validSuccessPlainJson("2026-12-31") + " extraTrailing";
        String cipherHex = encrypt(trailingJson);
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("尾随");
    }

    @Test
    @DisplayName("严格语义：时间戳非整数秒或缺失被拒绝")
    void rejectsInvalidTimestamp() {
        String invalidTsJson = "{\n" +
                "  \"timestamp\": \"1700000000\",\n" + // string instead of int
                "  \"nonce\": \"nonce1\",\n" +
                "  \"orderSn\": \"HS_1\",\n" +
                "  \"outOrderSn\": \"OUT_1\",\n" +
                "  \"num\": 1,\n" +
                "  \"totalPrice\": \"10.00\",\n" +
                "  \"status\": \"SUCCESS\",\n" +
                "  \"list\": [{\"type\": 201, \"code\": \"C\", \"pass\": \"P\"}]\n" +
                "}";
        String cipherHex = encrypt(invalidTsJson);
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    @DisplayName("严格语义：nonce 超过 64 字符或为空被拒绝")
    void rejectsInvalidNonce() {
        String longNonce = "n".repeat(65);
        String invalidNonceJson = "{\n" +
                "  \"timestamp\": 1700000000,\n" +
                "  \"nonce\": \"" + longNonce + "\",\n" +
                "  \"orderSn\": \"HS_1\",\n" +
                "  \"outOrderSn\": \"OUT_1\",\n" +
                "  \"num\": 1,\n" +
                "  \"totalPrice\": \"10.00\",\n" +
                "  \"status\": \"SUCCESS\",\n" +
                "  \"list\": [{\"type\": 201, \"code\": \"C\", \"pass\": \"P\"}]\n" +
                "}";
        String cipherHex = encrypt(invalidNonceJson);
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("nonce");
    }

    @Test
    @DisplayName("严格语义：num != 1 被拒绝")
    void rejectsNumNotEqualToOne() {
        String numTwoJson = "{\n" +
                "  \"timestamp\": 1700000000,\n" +
                "  \"nonce\": \"nonce1\",\n" +
                "  \"orderSn\": \"HS_1\",\n" +
                "  \"outOrderSn\": \"OUT_1\",\n" +
                "  \"num\": 2,\n" +
                "  \"totalPrice\": \"20.00\",\n" +
                "  \"status\": \"SUCCESS\",\n" +
                "  \"list\": [{\"type\": 201, \"code\": \"C1\", \"pass\": \"P1\"}, {\"type\": 201, \"code\": \"C2\", \"pass\": \"P2\"}]\n" +
                "}";
        String cipherHex = encrypt(numTwoJson);
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("num");
    }

    @Test
    @DisplayName("严格语义：状态非 SUCCESS 或 FAILED 被拒绝")
    void rejectsInvalidStatus() {
        String waitStatusJson = "{\n" +
                "  \"timestamp\": 1700000000,\n" +
                "  \"nonce\": \"nonce1\",\n" +
                "  \"orderSn\": \"HS_1\",\n" +
                "  \"outOrderSn\": \"OUT_1\",\n" +
                "  \"num\": 1,\n" +
                "  \"totalPrice\": \"10.00\",\n" +
                "  \"status\": \"WAIT\",\n" +
                "  \"list\": []\n" +
                "}";
        String cipherHex = encrypt(waitStatusJson);
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("status");
    }

    @ParameterizedTest
    @ValueSource(ints = {202, 203, 301, 302, 303, 304})
    @DisplayName("六型拒绝：非 Type 201 卡片类型在回调中均被拒绝")
    void rejectsOtherCardTypesForSuccess(int cardType) {
        String otherCardJson = "{\n" +
                "  \"timestamp\": 1700000000,\n" +
                "  \"nonce\": \"nonce1\",\n" +
                "  \"orderSn\": \"HS_1\",\n" +
                "  \"outOrderSn\": \"OUT_1\",\n" +
                "  \"num\": 1,\n" +
                "  \"totalPrice\": \"10.00\",\n" +
                "  \"status\": \"SUCCESS\",\n" +
                "  \"list\": [{\"type\": " + cardType + ", \"code\": \"C\", \"pass\": \"P\", \"url\": \"http://example.com\", \"crc\": \"CRC\"}]\n" +
                "}";
        String cipherHex = encrypt(otherCardJson);
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("201");
    }

    @Test
    @DisplayName("FAILED 规则：FAILED 包含非空卡券列表被拒绝")
    void rejectsFailedStatusWithNonEmptyList() {
        String failedWithCardsJson = "{\n" +
                "  \"timestamp\": 1700000000,\n" +
                "  \"nonce\": \"nonce1\",\n" +
                "  \"orderSn\": \"HS_1\",\n" +
                "  \"outOrderSn\": \"OUT_1\",\n" +
                "  \"num\": 1,\n" +
                "  \"totalPrice\": \"10.00\",\n" +
                "  \"status\": \"FAILED\",\n" +
                "  \"list\": [{\"type\": 201, \"code\": \"C\", \"pass\": \"P\"}]\n" +
                "}";
        String cipherHex = encrypt(failedWithCardsJson);
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    @DisplayName("FAILED 规则：FAILED 允许缺省或空数组，不提取卡片")
    void acceptsFailedStatusWithEmptyOrMissingList() {
        String failedJson = "{\n" +
                "  \"timestamp\": 1700000000,\n" +
                "  \"nonce\": \"nonce_failed_1\",\n" +
                "  \"orderSn\": \"HS_FAILED_1\",\n" +
                "  \"outOrderSn\": \"OUT_FAILED_1\",\n" +
                "  \"num\": 1,\n" +
                "  \"totalPrice\": \"10.00\",\n" +
                "  \"status\": \"FAILED\",\n" +
                "  \"list\": []\n" +
                "}";
        String cipherHex = encrypt(failedJson);
        String sign = sign(cipherHex);

        HuashengCallbackFact fact = HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        );

        assertThat(fact.status()).isEqualTo(HuashengOrderStatus.FAILED);
        assertThat(fact.orderSn()).isEqualTo("HS_FAILED_1");
        assertThat(fact.outOrderSn()).isEqualTo("OUT_FAILED_1");
        assertThat(fact.card()).isEmpty();
    }

    @Test
    @DisplayName("卡片规则：Type 201 卡片包含未声明的非空属性被拒绝")
    void rejectsCardWithUnknownNonEmptyFields() {
        String unknownFieldJson = "{\n" +
                "  \"timestamp\": 1700000000,\n" +
                "  \"nonce\": \"nonce1\",\n" +
                "  \"orderSn\": \"HS_1\",\n" +
                "  \"outOrderSn\": \"OUT_1\",\n" +
                "  \"num\": 1,\n" +
                "  \"totalPrice\": \"10.00\",\n" +
                "  \"status\": \"SUCCESS\",\n" +
                "  \"list\": [{\"type\": 201, \"code\": \"C\", \"pass\": \"P\", \"unknownKey\": \"someValue\"}]\n" +
                "}";
        String cipherHex = encrypt(unknownFieldJson);
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("未声明");
    }

    @Test
    @DisplayName("卡片规则：Type 201 缺少 code 或 pass 被拒绝")
    void rejectsCard201MissingCodeOrPass() {
        String missingPassJson = "{\n" +
                "  \"timestamp\": 1700000000,\n" +
                "  \"nonce\": \"nonce1\",\n" +
                "  \"orderSn\": \"HS_1\",\n" +
                "  \"outOrderSn\": \"OUT_1\",\n" +
                "  \"num\": 1,\n" +
                "  \"totalPrice\": \"10.00\",\n" +
                "  \"status\": \"SUCCESS\",\n" +
                "  \"list\": [{\"type\": 201, \"code\": \"C\"}]\n" +
                "}";
        String cipherHex = encrypt(missingPassJson);
        String sign = sign(cipherHex);

        assertThatThrownBy(() -> HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        )).isInstanceOf(HuashengCallbackParseException.class)
                .hasMessageContaining("pass");
    }

    @Test
    @DisplayName("成功解析：SUCCESS 恰一 Type 201 且完整保留三态 validTime 与原始价格字符串")
    void parsesSuccessCard201WithThreeStateValidTime() {
        String json = validSuccessPlainJson("2026-10-10 12:00:00");
        String cipherHex = encrypt(json);
        String sign = sign(cipherHex);

        HuashengCallbackFact fact = HuashengCallbackParser.parse(
                cipherHex.getBytes(StandardCharsets.UTF_8),
                APP_ID,
                sign,
                APP_ID,
                publicKey,
                APP_SECRET
        );

        assertThat(fact.status()).isEqualTo(HuashengOrderStatus.SUCCESS);
        assertThat(fact.orderSn()).isEqualTo("HS_ORDER_999");
        assertThat(fact.outOrderSn()).isEqualTo("OUT_ORDER_888");
        assertThat(fact.nonce()).isEqualTo("nonce_123456789");
        assertThat(fact.timestamp()).isEqualTo(1700000000L);
        assertThat(fact.num()).isEqualTo(1);
        assertThat(fact.rawTotalPrice()).isEqualTo("10.00");
        assertThat(fact.card()).isPresent();

        HuashengProtocolCard card = fact.card().get();
        assertThat(card.type()).isEqualTo(HuashengCardType.T201);
        assertThat(card.code().asOptional()).contains("CARD_CODE_001");
        assertThat(card.pass().asOptional()).contains("CARD_PASS_002");
        assertThat(card.validTime().isPresent()).isTrue();
        assertThat(card.validTime().asOptional()).contains("2026-10-10 12:00:00");
    }
}
