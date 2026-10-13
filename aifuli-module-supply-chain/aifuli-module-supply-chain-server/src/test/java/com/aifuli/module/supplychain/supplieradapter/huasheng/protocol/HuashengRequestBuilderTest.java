package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇请求构造与独立 JCA 契约测试。
 *
 * <p>职责：双盲验证华昇出站请求构造、密文组装与签名逻辑；测试端服务端解密与验签严格使用 Java 原生 JCA，绝不调用 HuashengCrypto 生成预期。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#123-真实本地-httpjca-契约、§16.4。
 */
class HuashengRequestBuilderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static KeyPair keyPair;
    private static String privateKeyPem;
    private static PublicKey publicKey;

    private static final String APP_ID = "HS_APP_123456789"; // 16 bytes UTF-8
    private static final String APP_SECRET = "HS_SEC_123456789"; // 16 bytes UTF-8
    private static final String BASE_URL = "https://hs.example.com";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-07T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final String FIXED_NONCE = "1234567890abcdef1234567890abcdef";

    @BeforeAll
    static void setUpKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();
        publicKey = keyPair.getPublic();
        PrivateKey priv = keyPair.getPrivate();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(priv.getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    private static HuashengCallContext createDefaultContext() {
        OperationTicket.CredentialMaterial material = new OperationTicket.CredentialMaterial(APP_SECRET, privateKeyPem);
        return new HuashengCallContext("HUA_SHENG", 1001L, BASE_URL, APP_ID, material, 3000, 5000, "OP_20261007_001", "TRACE_HS_001");
    }

    /**
     * 独立 JCA 服务端模拟解密与验签，绝不调用 HuashengCrypto。
     */
    private JsonNode decryptAndVerifyIndependently(HuashengWireRequest wire,
                                                   PublicKey pubKey,
                                                   String secret,
                                                   String appId) throws Exception {
        // 1. 校验基础请求头
        assertEquals("text/plain; charset=utf-8", wire.headers().get("Content-Type"));
        assertEquals(appId, wire.headers().get("X-AppId"));
        String signHex = wire.headers().get("X-Sign");
        assertNotNull(signHex, "X-Sign 不能为空");

        // 2. 独立 RSA SHA512withRSA 验签（原生 JCA，签名原文为小写 hex 密文字符串的 UTF-8 字节）
        Signature verifier = Signature.getInstance("SHA512withRSA");
        verifier.initVerify(pubKey);
        verifier.update(wire.bodyHex().getBytes(StandardCharsets.UTF_8));
        byte[] signBytes = HexFormat.of().parseHex(signHex);
        assertTrue(verifier.verify(signBytes), "原生 JCA RSA SHA512withRSA 验签应成功");

        // 3. 独立 AES/CBC/PKCS5Padding 解密（原生 JCA，以 secret 为密钥、appId 为 IV）
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(appId.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] cipherBytes = HexFormat.of().parseHex(wire.bodyHex());
        byte[] plainBytes = cipher.doFinal(cipherBytes);
        String plainJson = new String(plainBytes, StandardCharsets.UTF_8);

        return OBJECT_MAPPER.readTree(plainJson);
    }

    @Test
    @DisplayName("Cycle 1: TOKEN 请求构造、时间戳/Nonce 注入与独立 JCA 验签解密")
    void test01_TokenRequest_BuildAndIndependentJcaVerification() throws Exception {
        HuashengCallContext context = createDefaultContext();
        HuashengTokenRequest tokenReq = new HuashengTokenRequest(APP_ID, APP_SECRET);
        HuashengRequestBuilder builder = new HuashengRequestBuilder(FIXED_CLOCK, () -> FIXED_NONCE);

        HuashengWireRequest wire = builder.build(HuashengEndpoint.TOKEN, tokenReq, context, Optional.empty());

        assertNotNull(wire);
        assertEquals(HuashengEndpoint.TOKEN, wire.endpoint());
        assertEquals("https://hs.example.com/api/token", wire.url());
        assertEquals(3000, wire.connectTimeoutMs());
        assertEquals(5000, wire.readTimeoutMs());
        assertEquals("OP_20261007_001", wire.operationNo());
        assertEquals("TRACE_HS_001", wire.traceId());

        // TOKEN 请求头严禁携带 Authorization
        assertFalse(wire.headers().containsKey("Authorization"), "TOKEN 端点严禁携带 Authorization 请求头");

        // 独立 JCA 解密验签
        JsonNode payload = decryptAndVerifyIndependently(wire, publicKey, APP_SECRET, APP_ID);
        assertNotNull(payload);
        assertEquals(APP_ID, payload.get("app_id").asText());
        assertEquals(APP_SECRET, payload.get("app_secret").asText());
        assertEquals(FIXED_INSTANT.getEpochSecond(), payload.get("timestamp").asLong());
        assertEquals(FIXED_NONCE, payload.get("nonce").asText());
        assertNull(payload.get("data"), "顶层严禁包裹 data 节点");
    }

    @Test
    @DisplayName("Cycle 2: PLACE 请求构造、Bearer 头与独立 JCA 解密验签")
    void test02_PlaceRequest_BuildAndIndependentJca() throws Exception {
        HuashengCallContext context = createDefaultContext();
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest(
                "OUT_ORDER_20261007_001",
                8888L,
                1,
                600,
                "https://callback.example.com/notify",
                "13800138000"
        );
        HuashengRequestBuilder builder = new HuashengRequestBuilder(FIXED_CLOCK, () -> FIXED_NONCE);

        HuashengWireRequest wire = builder.build(
                HuashengEndpoint.PLACE,
                placeReq,
                context,
                Optional.of("HS_TOKEN_ABC123")
        );

        assertNotNull(wire);
        assertEquals(HuashengEndpoint.PLACE, wire.endpoint());
        assertEquals("https://hs.example.com/api/order/placeOrder", wire.url());
        assertEquals("Bearer HS_TOKEN_ABC123", wire.headers().get("Authorization"));

        // 独立 JCA 服务端解密验签
        JsonNode payload = decryptAndVerifyIndependently(wire, publicKey, APP_SECRET, APP_ID);
        assertNotNull(payload);
        assertEquals("OUT_ORDER_20261007_001", payload.get("outOrderSn").asText());
        assertEquals(8888L, payload.get("couponId").asLong());
        assertEquals(1, payload.get("num").asInt());
        assertEquals(600, payload.get("expireTime").asInt());
        assertEquals("https://callback.example.com/notify", payload.get("notifyUrl").asText());
        assertEquals("13800138000", payload.get("outMobile").asText());
        assertEquals(FIXED_INSTANT.getEpochSecond(), payload.get("timestamp").asLong());
        assertEquals(FIXED_NONCE, payload.get("nonce").asText());
        assertNull(payload.get("data"), "顶层严禁包裹 data 节点");

        // 测试缺省 outMobile 与缺省 expireTime (4参构造)
        HuashengPlaceRequest placeReqDefault = new HuashengPlaceRequest(
                "OUT_ORDER_20261007_002",
                9999L,
                1,
                "https://callback.example.com/notify2"
        );
        assertEquals(300, placeReqDefault.expireTime(), "expireTime缺省应规范化为300");
        assertTrue(placeReqDefault.outMobile().isEmpty(), "outMobile缺省应为空容器");

        HuashengWireRequest wireDefault = builder.build(
                HuashengEndpoint.PLACE,
                placeReqDefault,
                context,
                Optional.of("HS_TOKEN_DEF456")
        );
        JsonNode payloadDefault = decryptAndVerifyIndependently(wireDefault, publicKey, APP_SECRET, APP_ID);
        assertEquals(300, payloadDefault.get("expireTime").asInt());
        assertNull(payloadDefault.get("outMobile"), "缺省时 JSON 必须省略 outMobile 属性");
    }

    @Test
    void numericAndHistoricalMerchantOrderNumbersSurviveIndependentDecryption() throws Exception {
        for (String billno : java.util.List.of("202610131530277300000002", "BILL_01M4SJJ9SHWKPARFPWPJCQW39Q")) {
            var request = new HuashengPlaceRequest(billno, 8888L, 1, 600,
                    "https://callback.example.com/notify", "13800138000");
            var wire = new HuashengRequestBuilder(FIXED_CLOCK, () -> FIXED_NONCE).build(
                    HuashengEndpoint.PLACE, request, createDefaultContext(), Optional.of("HS_TOKEN_ABC123"));
            JsonNode payload = decryptAndVerifyIndependently(wire, publicKey, APP_SECRET, APP_ID);
            assertTrue(payload.get("outOrderSn").isTextual());
            assertEquals(billno, payload.get("outOrderSn").asText());
        }
    }

    @Test
    @DisplayName("Cycle 2: PLACE 请求输入参数守卫与不变量（反例覆盖）")
    void test02_PlaceRequest_ValidationGuards() {
        // 1. num 必须严格等于 1
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 2, 300, "https://hs.example.com/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 0, 300, "https://hs.example.com/notify"));

        // 2. couponId 必须大于 0
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 0L, 1, 300, "https://hs.example.com/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", -1L, 1, 300, "https://hs.example.com/notify"));

        // 3. expireTime 严格在 [60, 1800]，严禁钳制
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 59, "https://hs.example.com/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 1801, "https://hs.example.com/notify"));

        // 4. notifyUrl 必须为绝对 HTTP/HTTPS、无 userinfo/fragment、非私网/本地字面量、<= 2048
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "/relative/url"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "ftp://callback.example.com/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "https://user:pass@callback.example.com/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "https://callback.example.com/notify#fragment"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "http://localhost/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "http://127.0.0.1/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "http://10.0.0.1/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "http://192.168.1.1/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "http://172.16.0.1/notify"));

        // 5. outOrderSn 校验：非空非空白、无首尾空格、无控制字符、<= 128
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("", 100L, 1, 300, "https://hs.example.com/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("   ", 100L, 1, 300, "https://hs.example.com/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest(" OUT_1", 100L, 1, 300, "https://hs.example.com/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1 ", 100L, 1, 300, "https://hs.example.com/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_\n1", 100L, 1, 300, "https://hs.example.com/notify"));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("A".repeat(129), 100L, 1, 300, "https://hs.example.com/notify"));

        // 6. outMobile 可选值校验：非空白、<= 64 字节
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "https://hs.example.com/notify", "  "));
        assertThrows(IllegalArgumentException.class, () ->
                new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "https://hs.example.com/notify", "1".repeat(65)));
    }

    @Test
    @DisplayName("Cycle 3: CONFIRM、QUERY、TAKE 请求构造与独立 JCA 解密验签")
    void test03_ConfirmQueryTake_BuildAndIndependentJca() throws Exception {
        HuashengCallContext context = createDefaultContext();
        HuashengRequestBuilder builder = new HuashengRequestBuilder(FIXED_CLOCK, () -> FIXED_NONCE);

        // 1. CONFIRM
        HuashengConfirmRequest confirmReq = new HuashengConfirmRequest("HS_CONFIRM_001");
        HuashengWireRequest confirmWire = builder.build(
                HuashengEndpoint.CONFIRM,
                confirmReq,
                context,
                Optional.of("HS_TOKEN_CONFIRM")
        );
        assertEquals("https://hs.example.com/api/order/confirmOrder", confirmWire.url());
        assertEquals("Bearer HS_TOKEN_CONFIRM", confirmWire.headers().get("Authorization"));
        JsonNode confirmPayload = decryptAndVerifyIndependently(confirmWire, publicKey, APP_SECRET, APP_ID);
        assertEquals("HS_CONFIRM_001", confirmPayload.get("orderSn").asText());
        assertEquals(FIXED_INSTANT.getEpochSecond(), confirmPayload.get("timestamp").asLong());
        assertEquals(FIXED_NONCE, confirmPayload.get("nonce").asText());

        // 2. QUERY byOrderSn（恰 1 字段序列化）
        HuashengQueryRequest queryByOrderSn = HuashengQueryRequest.byOrderSn("HS_QUERY_002");
        HuashengWireRequest queryWire1 = builder.build(
                HuashengEndpoint.QUERY,
                queryByOrderSn,
                context,
                Optional.of("HS_TOKEN_QUERY")
        );
        assertEquals("https://hs.example.com/api/order/queryOrder", queryWire1.url());
        JsonNode queryPayload1 = decryptAndVerifyIndependently(queryWire1, publicKey, APP_SECRET, APP_ID);
        assertEquals("HS_QUERY_002", queryPayload1.get("orderSn").asText());
        assertNull(queryPayload1.get("outOrderSn"), "byOrderSn 时严禁输出 outOrderSn 字段");

        // 3. QUERY byOutOrderSn（恰 1 字段序列化）
        HuashengQueryRequest queryByOutOrderSn = HuashengQueryRequest.byOutOrderSn("OUT_QUERY_003");
        HuashengWireRequest queryWire2 = builder.build(
                HuashengEndpoint.QUERY,
                queryByOutOrderSn,
                context,
                Optional.of("HS_TOKEN_QUERY")
        );
        JsonNode queryPayload2 = decryptAndVerifyIndependently(queryWire2, publicKey, APP_SECRET, APP_ID);
        assertEquals("OUT_QUERY_003", queryPayload2.get("outOrderSn").asText());
        assertNull(queryPayload2.get("orderSn"), "byOutOrderSn 时严禁输出 orderSn 字段");

        // 4. TAKE
        HuashengTakeRequest takeReq = new HuashengTakeRequest("HS_TAKE_004");
        HuashengWireRequest takeWire = builder.build(
                HuashengEndpoint.TAKE,
                takeReq,
                context,
                Optional.of("HS_TOKEN_TAKE")
        );
        assertEquals("https://hs.example.com/api/order/takeCard", takeWire.url());
        JsonNode takePayload = decryptAndVerifyIndependently(takeWire, publicKey, APP_SECRET, APP_ID);
        assertEquals("HS_TAKE_004", takePayload.get("orderSn").asText());
        assertEquals(FIXED_INSTANT.getEpochSecond(), takePayload.get("timestamp").asLong());
        assertEquals(FIXED_NONCE, takePayload.get("nonce").asText());
    }

    @Test
    @DisplayName("Cycle 3: CONFIRM、QUERY、TAKE 单号校验守卫（反例覆盖）")
    void test03_ConfirmQueryTake_ValidationGuards() {
        // CONFIRM orderSn
        assertThrows(IllegalArgumentException.class, () -> new HuashengConfirmRequest(null));
        assertThrows(IllegalArgumentException.class, () -> new HuashengConfirmRequest(""));
        assertThrows(IllegalArgumentException.class, () -> new HuashengConfirmRequest("   "));
        assertThrows(IllegalArgumentException.class, () -> new HuashengConfirmRequest(" HS_1"));
        assertThrows(IllegalArgumentException.class, () -> new HuashengConfirmRequest("HS_1 "));
        assertThrows(IllegalArgumentException.class, () -> new HuashengConfirmRequest("HS_\t1"));
        assertThrows(IllegalArgumentException.class, () -> new HuashengConfirmRequest("B".repeat(129)));

        // QUERY byOrderSn / byOutOrderSn
        assertThrows(IllegalArgumentException.class, () -> HuashengQueryRequest.byOrderSn(""));
        assertThrows(IllegalArgumentException.class, () -> HuashengQueryRequest.byOrderSn("  "));
        assertThrows(IllegalArgumentException.class, () -> HuashengQueryRequest.byOrderSn(" HS_1"));
        assertThrows(IllegalArgumentException.class, () -> HuashengQueryRequest.byOrderSn("HS_\r1"));
        assertThrows(IllegalArgumentException.class, () -> HuashengQueryRequest.byOutOrderSn(""));
        assertThrows(IllegalArgumentException.class, () -> HuashengQueryRequest.byOutOrderSn(" "));
        assertThrows(IllegalArgumentException.class, () -> HuashengQueryRequest.byOutOrderSn(" OUT_1"));
        assertThrows(IllegalArgumentException.class, () -> HuashengQueryRequest.byOutOrderSn("OUT_\n1"));

        // TAKE orderSn
        assertThrows(IllegalArgumentException.class, () -> new HuashengTakeRequest(null));
        assertThrows(IllegalArgumentException.class, () -> new HuashengTakeRequest(""));
        assertThrows(IllegalArgumentException.class, () -> new HuashengTakeRequest(" "));
        assertThrows(IllegalArgumentException.class, () -> new HuashengTakeRequest(" HS_1"));
        assertThrows(IllegalArgumentException.class, () -> new HuashengTakeRequest("HS_1 "));
        assertThrows(IllegalArgumentException.class, () -> new HuashengTakeRequest("HS_\01"));
    }

    @Test
    @DisplayName("Cycle 4: 独立 JCA 负控制（密文单字节篡改、签名单字节篡改、错误公钥、错误 AES 密钥）")
    void test04_IndependentJca_NegativeTamperingAndWrongKeys() throws Exception {
        HuashengCallContext context = createDefaultContext();
        HuashengConfirmRequest req = new HuashengConfirmRequest("HS_TAMPER_001");
        HuashengRequestBuilder builder = new HuashengRequestBuilder(FIXED_CLOCK, () -> FIXED_NONCE);
        HuashengWireRequest wire = builder.build(
                HuashengEndpoint.CONFIRM,
                req,
                context,
                Optional.of("HS_TOKEN_123")
        );

        // 1. 密文首字节篡改（篡改 bodyHex 第一位十六进制字符，破坏签名与明文结构）
        char originalFirstChar = wire.bodyHex().charAt(0);
        char tamperedChar = originalFirstChar == 'a' ? 'b' : 'a';
        String tamperedBodyHex = tamperedChar + wire.bodyHex().substring(1);

        // 验签必须失败
        Signature verifier = Signature.getInstance("SHA512withRSA");
        verifier.initVerify(publicKey);
        verifier.update(tamperedBodyHex.getBytes(StandardCharsets.UTF_8));
        byte[] signBytes = HexFormat.of().parseHex(wire.headers().get("X-Sign"));
        assertFalse(verifier.verify(signBytes), "密文遭篡改后，原生 RSA 验签必须失败");

        // 首块解密后损坏，无法作为合法 JSON 解析
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(APP_ID.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] decryptedBytes = cipher.doFinal(HexFormat.of().parseHex(tamperedBodyHex));
        assertThrows(Exception.class, () -> OBJECT_MAPPER.readTree(decryptedBytes),
                "密文首块篡改后，解密数据损坏无法解析为合法 JSON");

        // 尾块填充遭篡改后，AES 解密填充校验必须抛出异常
        int lastIdx = wire.bodyHex().length() - 1;
        char lastChar = wire.bodyHex().charAt(lastIdx);
        char tamperedLastChar = lastChar == '0' ? '1' : '0';
        String tamperedPaddingHex = wire.bodyHex().substring(0, lastIdx) + tamperedLastChar;
        assertThrows(Exception.class, () -> cipher.doFinal(HexFormat.of().parseHex(tamperedPaddingHex)),
                "密文尾块填充遭篡改后，原生 AES 解密必须抛出异常");

        // 2. 签名单字节篡改
        String originalSignHex = wire.headers().get("X-Sign");
        char signFirst = originalSignHex.charAt(0);
        char tamperedSignFirst = signFirst == 'a' ? 'b' : 'a';
        String tamperedSignHex = tamperedSignFirst + originalSignHex.substring(1);

        Signature signTamperVerifier = Signature.getInstance("SHA512withRSA");
        signTamperVerifier.initVerify(publicKey);
        signTamperVerifier.update(wire.bodyHex().getBytes(StandardCharsets.UTF_8));
        assertFalse(signTamperVerifier.verify(HexFormat.of().parseHex(tamperedSignHex)),
                "签名遭篡改后，原生 RSA 验签必须失败");

        // 3. 错误公钥验签
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        PublicKey wrongPublicKey = kpg.generateKeyPair().getPublic();

        Signature wrongKeyVerifier = Signature.getInstance("SHA512withRSA");
        wrongKeyVerifier.initVerify(wrongPublicKey);
        wrongKeyVerifier.update(wire.bodyHex().getBytes(StandardCharsets.UTF_8));
        assertFalse(wrongKeyVerifier.verify(signBytes), "使用错误公钥时，原生 RSA 验签必须失败");

        // 4. 错误 AES 密钥解密
        SecretKeySpec wrongKeySpec = new SecretKeySpec("WRONG_SEC_123456".getBytes(StandardCharsets.UTF_8), "AES");
        Cipher wrongCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        wrongCipher.init(Cipher.DECRYPT_MODE, wrongKeySpec, ivSpec);
        assertThrows(Exception.class, () -> wrongCipher.doFinal(HexFormat.of().parseHex(wire.bodyHex())),
                "使用错误 AES 密钥时，原生 AES 解密必须抛出异常");
    }

    @Test
    @DisplayName("Cycle 4: 端点匹配、Token 必需性与 appId 一致性前置守卫")
    void test04_EndpointAndTokenValidationGuards() {
        HuashengCallContext context = createDefaultContext();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();

        // 1. 端点与请求类型不匹配
        HuashengTokenRequest tokenReq = new HuashengTokenRequest(APP_ID, APP_SECRET);
        assertThrows(IllegalArgumentException.class, () ->
                builder.build(HuashengEndpoint.PLACE, tokenReq, context, Optional.empty()));

        HuashengConfirmRequest confirmReq = new HuashengConfirmRequest("HS_ORD_001");
        assertThrows(IllegalArgumentException.class, () ->
                builder.build(HuashengEndpoint.TOKEN, confirmReq, context, Optional.empty()));

        // 2. 业务端点缺少 Token 必须快速失败
        assertThrows(IllegalArgumentException.class, () ->
                builder.build(HuashengEndpoint.CONFIRM, confirmReq, context, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () ->
                builder.build(HuashengEndpoint.CONFIRM, confirmReq, context, Optional.of("   ")));

        // 3. TokenRequest 的 appId 与 CallContext 的 appId 不一致
        HuashengTokenRequest diffAppIdReq = new HuashengTokenRequest("DIFFERENT_APP_ID", APP_SECRET);
        assertThrows(IllegalArgumentException.class, () ->
                builder.build(HuashengEndpoint.TOKEN, diffAppIdReq, context, Optional.empty()));

        // 4. 非法 Nonce 与非法时间戳守卫
        HuashengRequestBuilder badNonceBuilder = new HuashengRequestBuilder(FIXED_CLOCK, () -> "short_nonce");
        assertThrows(IllegalArgumentException.class, () ->
                badNonceBuilder.build(HuashengEndpoint.TOKEN, tokenReq, context, Optional.empty()));

        Clock pastClock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC); // epoch second = 0
        HuashengRequestBuilder zeroTsBuilder = new HuashengRequestBuilder(pastClock, () -> FIXED_NONCE);
        assertThrows(IllegalArgumentException.class, () ->
                zeroTsBuilder.build(HuashengEndpoint.TOKEN, tokenReq, context, Optional.empty()));
    }

    @Test
    @DisplayName("Cycle 4: 每次物理发送独立生成全新 Nonce 与时间戳（严禁跨端点复用）")
    void test04_IndependentPhysicalSendNonceAndTimestampPerCall() {
        HuashengCallContext context = createDefaultContext();
        HuashengTokenRequest tokenReq = new HuashengTokenRequest(APP_ID, APP_SECRET);
        HuashengRequestBuilder dynamicBuilder = new HuashengRequestBuilder();

        HuashengWireRequest send1 = dynamicBuilder.build(HuashengEndpoint.TOKEN, tokenReq, context, Optional.empty());
        HuashengWireRequest send2 = dynamicBuilder.build(HuashengEndpoint.TOKEN, tokenReq, context, Optional.empty());

        assertNotNull(send1.bodyHex());
        assertNotNull(send2.bodyHex());
        assertNotEquals(send1.bodyHex(), send2.bodyHex(), "每次物理发送必须由新 Nonce 与新时间戳生成全新密文");
        assertNotEquals(send1.headers().get("X-Sign"), send2.headers().get("X-Sign"), "每次物理发送签名必须独立生成");
    }

    @Test
    @DisplayName("Cycle 4: 敏感模型 toString 脱敏与意外 Jackson 序列化严格拒绝")
    void test04_SensitiveToStringAndJacksonSerializationRejection() {
        HuashengCallContext context = createDefaultContext();
        HuashengTokenRequest tokenReq = new HuashengTokenRequest(APP_ID, APP_SECRET);
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest("OUT_1", 100L, 1, 300, "https://hs.example.com/notify", "13800138000");
        HuashengConfirmRequest confirmReq = new HuashengConfirmRequest("HS_CONFIRM_1");
        HuashengQueryRequest queryReq = HuashengQueryRequest.byOrderSn("HS_QUERY_1");
        HuashengTakeRequest takeReq = new HuashengTakeRequest("HS_TAKE_1");

        HuashengRequestBuilder builder = new HuashengRequestBuilder(FIXED_CLOCK, () -> FIXED_NONCE);
        HuashengWireRequest wire = builder.build(HuashengEndpoint.TOKEN, tokenReq, context, Optional.empty());

        // 1. toString 严格脱敏
        String ctxStr = context.toString();
        assertFalse(ctxStr.contains(APP_SECRET), "CallContext toString 严禁泄露密钥");
        assertFalse(ctxStr.contains(privateKeyPem), "CallContext toString 严禁泄露私钥");

        String tokenReqStr = tokenReq.toString();
        assertFalse(tokenReqStr.contains(APP_SECRET), "TokenRequest toString 严禁泄露密钥");
        assertTrue(tokenReqStr.contains("***"), "TokenRequest toString 必须显示脱敏掩码");

        String placeReqStr = placeReq.toString();
        assertFalse(placeReqStr.contains("13800138000"), "PlaceRequest toString 严禁泄露手机号");
        assertTrue(placeReqStr.contains("[REDACTED]"), "PlaceRequest toString 必须对手机号掩码");

        String wireStr = wire.toString();
        assertFalse(wireStr.contains(wire.bodyHex()), "WireRequest toString 严禁输出密文正文");
        assertFalse(wireStr.contains(wire.headers().get("X-Sign")), "WireRequest toString 严禁输出签名");

        // 2. Jackson 序列化严格拒绝（类级 @JsonSerialize(using = HuashengInternalOnlySerializer.class)）
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(Exception.class, () -> mapper.writeValueAsString(context));
        assertThrows(Exception.class, () -> mapper.writeValueAsString(tokenReq));
        assertThrows(Exception.class, () -> mapper.writeValueAsString(placeReq));
        assertThrows(Exception.class, () -> mapper.writeValueAsString(confirmReq));
        assertThrows(Exception.class, () -> mapper.writeValueAsString(queryReq));
        assertThrows(Exception.class, () -> mapper.writeValueAsString(takeReq));
        assertThrows(Exception.class, () -> mapper.writeValueAsString(wire));
    }
}
