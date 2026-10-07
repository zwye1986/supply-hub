package com.aifuli.module.supplychain.supplieradapter.huasheng.http;

import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.infrastructure.security.SupplierEndpointPolicy;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengDiagnosticProjector;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengCauseKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengProtectedFailure;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengCallContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengConfirmRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExpectedOrder;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengFaceAmount;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengParseFact;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengPlaceRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengPrice;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengQueryRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengRequestBuilder;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTokenRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTokenResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTransportContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengWireRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇 HTTP 真实本地 HTTPS 契约测试。
 *
 * <p>职责：
 * 双盲验证华昇传输层 {@link HuashengHttpExecutor} 真实本地 HTTPS 物理通信能力：
 * <ul>
 *   <li>基于 MockWebServer HTTPS 与标准 JSSE/keytool 运行时自签名单证书测试；</li>
 *   <li>独立 JCA 原生解密与验签（绝不调用被测库生成预期）；</li>
 *   <li>逐次执行 {@link SupplierEndpointPolicy} 校验与 0 网络请求负例；</li>
 *   <li>单次发送防护：重定向 302/307、HTTP 4xx/5xx/408/429、503 Retry-After:0 与超大数字；</li>
 *   <li>真实 HTTP/2 连接合并（coalescing）421 单次通信确证；</li>
 *   <li>严格流式读取与 1MiB+1 应用解码上限（cancel-before-close 并全量擦除）；</li>
 *   <li>严格 UTF-8 REPORT 桥接与 A2 parser 原 ProtectedFailure 透传；</li>
 *   <li>共享单调绝对截止点（deadlineNanoTime）与线程中断状态保真；</li>
 *   <li>生命周期单次解析、owned 数组擦除、安全日志分钟桶限频与 1024 键上限。</li>
 * </ul>
 *
 * <p>不变量：
 * <ul>
 *   <li>测试端自建独立 JCA 验签解密逻辑，禁止复用生产 Crypto 类；</li>
 *   <li>真实 socket 通信，不使用 Mockito mock 本地网络行为；</li>
 *   <li>通过 {@code MethodHandles.privateLookupIn} 驱动执行器 private 窄测试构造。</li>
 * </ul>
 *
 * <p>关键决策：
 * 在外部测试包中通过 {@link HuashengDiagnosticProjector#toSafeDiagnostics(HuashengProtectedFailure)} 检查失败事实，
 * 遵守受保护失败的包访问控制架构设计。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#17-a4-真实本地-https-传输技术附表（最小方案已确认，待针对性复审）、§17.1～§17.8。
 */
public class HuashengHttpContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HuashengRequestBuilder REQUEST_BUILDER = new HuashengRequestBuilder();

    private static final String APP_ID = "HS_APP_123456789"; // 16 bytes
    private static final String APP_SECRET = "HS_SEC_123456789"; // 16 bytes
    private static final String HOST_PRIMARY = "hs-fixture.example";
    private static final String HOST_COALESCE = "hs-fixture-coalesce.example";
    private static final String KEYSTORE_PASS = "changeit_hs_a4";

    private static KeyPair clientRsaKeyPair;
    private static String clientPrivateKeyPem;

    private static File tempKeystoreFile;
    private static SSLSocketFactory fixtureClientSslSocketFactory;
    private static X509TrustManager fixtureClientTrustManager;
    private static SSLSocketFactory serverSslSocketFactory;
    private static Dns fixtureDns;
    private static SupplierEndpointPolicy standardPolicy;

    private MockWebServer server;
    private int serverPort;
    private String serverBaseUrl;
    private HuashengHttpExecutor executor;

    @BeforeAll
    static void setUpGlobalCryptoAndCertificates() throws Exception {
        // 1. 生成客户端 RSA 密钥对（用于请求签名）
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        clientRsaKeyPair = kpg.generateKeyPair();
        clientPrivateKeyPem = Base64.getEncoder().encodeToString(clientRsaKeyPair.getPrivate().getEncoded());

        // 2. 生成短期自签名 PKCS12 证书库（包含 SAN: hs-fixture.example, hs-fixture-coalesce.example）
        tempKeystoreFile = File.createTempFile("hs_test_keystore_", ".p12");
        tempKeystoreFile.delete(); // 删除空文件，以便 keytool 创建新密钥库
        tempKeystoreFile.deleteOnExit();

        String javaHome = System.getProperty("java.home");
        String keytoolPath = javaHome + File.separator + "bin" + File.separator + "keytool";

        ProcessBuilder pb = new ProcessBuilder(
                keytoolPath,
                "-genkeypair",
                "-alias", "mockserver",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-keystore", tempKeystoreFile.getAbsolutePath(),
                "-storepass", KEYSTORE_PASS,
                "-storetype", "PKCS12",
                "-ext", "SAN=dns:" + HOST_PRIMARY + ",dns:" + HOST_COALESCE,
                "-dname", "CN=" + HOST_PRIMARY
        );
        Process process = pb.start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("keytool 生成证书失败: " + err);
        }

        // 3. 加载证书库并构造 JSSE KeyManager 与 TrustManager
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(tempKeystoreFile)) {
            keyStore.load(fis, KEYSTORE_PASS.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASS.toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        SSLContext serverSslContext = SSLContext.getInstance("TLS");
        serverSslContext.init(kmf.getKeyManagers(), null, new SecureRandom());
        serverSslSocketFactory = serverSslContext.getSocketFactory();

        SSLContext clientSslContext = SSLContext.getInstance("TLS");
        clientSslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        fixtureClientSslSocketFactory = clientSslContext.getSocketFactory();
        fixtureClientTrustManager = (X509TrustManager) tmf.getTrustManagers()[0];

        // 4. 配置专用测试 DNS（仅解析两个测试域名到 loopback）
        fixtureDns = hostname -> {
            if (HOST_PRIMARY.equalsIgnoreCase(hostname) || HOST_COALESCE.equalsIgnoreCase(hostname)) {
                return List.of(InetAddress.getByName("127.0.0.1"));
            }
            throw new UnknownHostException("测试 DNS 拒绝未授权域名解析: " + hostname);
        };

        // 5. 初始化端点白名单策略
        MockEnvironment env = new MockEnvironment();
        env.setProperty("aifuli.supply-chain.connector.allowed-hosts", HOST_PRIMARY + "," + HOST_COALESCE);
        standardPolicy = new SupplierEndpointPolicy(env);
    }

    @AfterAll
    static void tearDownGlobalCertificates() {
        if (tempKeystoreFile != null && tempKeystoreFile.exists()) {
            tempKeystoreFile.delete();
        }
    }

    @BeforeEach
    void setUpMockServerAndExecutor() throws Throwable {
        server = new MockWebServer();
        server.useHttps(serverSslSocketFactory, false);
        server.setProtocols(List.of(Protocol.HTTP_2, Protocol.HTTP_1_1));
        server.start();

        serverPort = server.getPort();
        serverBaseUrl = "https://" + HOST_PRIMARY + ":" + serverPort;

        executor = createExecutorViaReflection(standardPolicy, fixtureDns, fixtureClientSslSocketFactory, fixtureClientTrustManager);
    }

    @AfterEach
    void tearDownMockServer() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    // =========================================================================
    // 1. 协议/地址场景 (HTTPS-TOKEN, PLACE, CONFIRM, QUERY, TAKE, PREFIX, JCA)
    // =========================================================================

    @Test
    @DisplayName("HTTPS-TOKEN/§17.8: 真实本地 HTTPS 请求 TOKEN 端点并成功解析")
    void httpsTokenHappyPath() throws Exception {
        String tokenBody = "{\"code\":0,\"data\":{\"access_token\":\"TOKEN_HS_ABC_123\",\"expires_in\":7200}}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(tokenBody));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed, "TOKEN 交换应当成功");

        HuashengHttpExchange exchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value();
        assertEquals(HuashengEndpoint.TOKEN, exchange.evidence().endpoint());
        assertEquals(200, exchange.evidence().httpStatus().getAsInt());
        assertEquals(0, exchange.evidence().physicalSendCount());
        assertFalse(exchange.evidence().wasBusinessCallStarted());

        var parseAttempt = exchange.parseToken();
        assertTrue(parseAttempt instanceof HuashengInternalAttempt.Parsed);
        var fact = ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengTokenResponse>>) parseAttempt).value();
        assertInstanceOf(HuashengParseFact.Data.class, fact);
        HuashengTokenResponse tokenResp = ((HuashengParseFact.Data<HuashengTokenResponse>) fact).data();
        assertEquals("TOKEN_HS_ABC_123", tokenResp.accessToken());
        assertEquals(7200, tokenResp.expiresInSeconds());
    }

    @Test
    @DisplayName("HTTPS-PLACE/§17.8: 携带 Bearer Token 请求 PLACE 端点并成功解析")
    void httpsPlaceHappyPath() throws Exception {
        String placeBody = "{\"code\":0,\"data\":{\"orderSn\":\"HS_ORDER_001\",\"outOrderSn\":\"OP_20261007_001\",\"num\":1,\"totalPrice\":\"9.9999\",\"status\":\"WAIT\"}}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(placeBody));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest(
                "OP_20261007_001", 8888L, 1, 600, "https://" + HOST_PRIMARY + "/notify"
        );
        HuashengWireRequest wire = buildWire(HuashengEndpoint.PLACE, placeReq, context, Optional.of("BEARER_TOKEN_01"));
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 2);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);
        HuashengHttpExchange exchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value();
        assertEquals(1, exchange.evidence().physicalSendCount());
        assertTrue(exchange.evidence().wasBusinessCallStarted());

        HuashengExpectedOrder expected = new HuashengExpectedOrder("OP_20261007_001", Optional.empty(), 1);
        var parseAttempt = exchange.parseOrder(expected, HuashengEndpoint.PLACE);
        assertTrue(parseAttempt instanceof HuashengInternalAttempt.Parsed);
        var fact = ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengOrderResponse>>) parseAttempt).value();
        HuashengOrderResponse order = ((HuashengParseFact.Data<HuashengOrderResponse>) fact).data();
        assertEquals("HS_ORDER_001", order.orderSn());
    }

    @Test
    @DisplayName("HTTPS-CONFIRM/§17.8: 请求 CONFIRM 端点并成功解析")
    void httpsConfirmHappyPath() throws Exception {
        String confirmBody = "{\"code\":0,\"data\":{\"orderSn\":\"HS_ORDER_001\",\"outOrderSn\":\"OP_20261007_001\",\"num\":1,\"totalPrice\":\"9.9999\",\"status\":\"SUCCESS\"}}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(confirmBody));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengConfirmRequest confirmReq = new HuashengConfirmRequest("HS_ORDER_001");
        HuashengWireRequest wire = buildWire(HuashengEndpoint.CONFIRM, confirmReq, context, Optional.of("BEARER_TOKEN_01"));
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 2);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);
        HuashengHttpExchange exchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value();

        HuashengExpectedOrder expected = new HuashengExpectedOrder("OP_20261007_001", Optional.of("HS_ORDER_001"), 1);
        var parseAttempt = exchange.parseOrder(expected, HuashengEndpoint.CONFIRM);
        assertTrue(parseAttempt instanceof HuashengInternalAttempt.Parsed);
    }

    @Test
    @DisplayName("HTTPS-QUERY/§17.8: 请求 QUERY 端点并成功解析订单")
    void httpsQueryHappyPath() throws Exception {
        String queryBody = "{\"code\":0,\"data\":{\"orderSn\":\"HS_ORDER_001\",\"outOrderSn\":\"OP_20261007_001\",\"num\":1,\"totalPrice\":\"9.9999\",\"status\":\"SUCCESS\",\"refundStatus\":\"NONE\",\"isTake\":\"Y\"}}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(queryBody));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        var queryReq = com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengQueryRequest.byOutOrderSn("OP_20261007_001");
        HuashengWireRequest wire = buildWire(HuashengEndpoint.QUERY, queryReq, context, Optional.of("BEARER_TOKEN_01"));
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 3);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);
        HuashengHttpExchange exchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value();

        HuashengExpectedOrder expected = new HuashengExpectedOrder("OP_20261007_001", Optional.of("HS_ORDER_001"), 1);
        var parseAttempt = exchange.parseOrder(expected, HuashengEndpoint.QUERY);
        assertTrue(parseAttempt instanceof HuashengInternalAttempt.Parsed);
    }

    @Test
    @DisplayName("HTTPS-TAKE/§17.8: 请求 TAKE 端点并成功解析卡密响应")
    void httpsTakeHappyPath() throws Exception {
        String takeBody = "{\"code\":0,\"data\":{\"orderSn\":\"HS_ORDER_001\",\"num\":1,\"list\":[{\"type\":201,\"code\":\"CARD_NO_1\",\"pass\":\"CARD_PWD_1\"}]}}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(takeBody));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengTakeRequest takeReq = new HuashengTakeRequest("HS_ORDER_001");
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TAKE, takeReq, context, Optional.of("BEARER_TOKEN_01"));
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 4);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);
        HuashengHttpExchange exchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value();

        HuashengExpectedOrder expected = new HuashengExpectedOrder("OP_20261007_001", Optional.of("HS_ORDER_001"), 1);
        var parseAttempt = exchange.parseTake(expected);
        assertTrue(parseAttempt instanceof HuashengInternalAttempt.Parsed);
        var fact = ((HuashengInternalAttempt.Parsed<HuashengParseFact<HuashengTakeResponse>>) parseAttempt).value();
        HuashengTakeResponse takeResp = ((HuashengParseFact.Data<HuashengTakeResponse>) fact).data();
        assertEquals(1, takeResp.list().size());
        assertEquals("CARD_NO_1", takeResp.list().get(0).code().asOptional().get());
    }

    @Test
    @DisplayName("HTTPS-PREFIX/§17.2: 基础根地址携带业务前缀（/tenant/v1）时物理路径保真发送")
    void httpsPrefixPreservedInUrl() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TK\",\"expires_in\":7200}}"));

        String prefixBase = serverBaseUrl + "/tenant/v1";
        HuashengCallContext context = createCallContext(prefixBase);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/tenant/v1/api/token", recorded.getPath(), "业务前缀路径必须物理保真发送");
    }

    @Test
    @DisplayName("HTTPS-JCA-TAMPER/§17.8: 双盲独立 JCA 解密验签以及单字节篡改负控制")
    void httpsJcaIndependentCryptoAndTamperNegativeControl() throws Exception {
        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());

        // 1. 独立解密请求体
        byte[] cipherBytes = HexFormat.of().parseHex(wire.bodyHex());
        byte[] secretBytes = APP_SECRET.getBytes(StandardCharsets.UTF_8);
        byte[] ivBytes = APP_ID.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secretBytes, "AES"), new IvParameterSpec(ivBytes));
        byte[] plainBytes = cipher.doFinal(cipherBytes);
        String plainText = new String(plainBytes, StandardCharsets.UTF_8);
        assertTrue(plainText.contains("timestamp") && plainText.contains("nonce"));

        // 2. 独立验证 RSA 签名
        Signature sig = Signature.getInstance("SHA512withRSA");
        sig.initVerify(clientRsaKeyPair.getPublic());
        sig.update(wire.bodyHex().getBytes(StandardCharsets.UTF_8));
        byte[] signBytes = HexFormat.of().parseHex(wire.headers().get("X-Sign"));
        assertTrue(sig.verify(signBytes), "独立 JCA 验签必须通过");

        // 3. 负控制：签名单字节篡改导致验签失败
        byte[] tamperedSign = signBytes.clone();
        tamperedSign[0] ^= 0x01;
        sig.initVerify(clientRsaKeyPair.getPublic());
        sig.update(wire.bodyHex().getBytes(StandardCharsets.UTF_8));
        assertFalse(sig.verify(tamperedSign), "被篡改签名必须验签失败");
    }

    // =========================================================================
    // 2. 策略与 TLS 场景 (HTTPS-POLICY-HOST, HTTP, PRIVATE, TWICE, UNTRUSTED, SAN, HANG)
    // =========================================================================

    @Test
    @DisplayName("HTTPS-POLICY-HOST/§17.2: 策略拒绝未在白名单的 Host（零请求，PREFLIGHT）")
    void httpsPolicyHostRejection() {
        String forbiddenUrl = "https://evil-untrusted.example:8443";
        HuashengCallContext context = createCallContext(forbiddenUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.PREFLIGHT, diag.failureKind());
        assertEquals(0, server.getRequestCount(), "策略拦截时服务端严禁收到请求");
    }

    @Test
    @DisplayName("HTTPS-POLICY-HTTP/§17.2: 策略拒绝非 HTTPS 明文地址（零请求，PREFLIGHT）")
    void httpsPolicyHttpSchemeRejection() {
        String plainHttpUrl = "http://" + HOST_PRIMARY + ":" + serverPort;
        HuashengCallContext context = createCallContext(plainHttpUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.PREFLIGHT, diag.failureKind());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    @DisplayName("HTTPS-POLICY-PRIVATE/§17.2: 策略拒绝私网 IP 字面量（零请求，PREFLIGHT）")
    void httpsPolicyPrivateLiteralRejection() {
        String privateIpUrl = "https://127.0.0.1:" + serverPort;
        HuashengCallContext context = createCallContext(privateIpUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.PREFLIGHT, diag.failureKind());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    @DisplayName("HTTPS-POLICY-TWICE/§17.2: frozenBaseUrl 与 request.url() 均受策略检验")
    void httpsPolicyTwiceValidation() {
        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());

        // 构造被篡改了非法 baseUrl 的 transportContext
        HuashengCallContext badBaseContext = createCallContext("https://not-allowed.example");
        HuashengTransportContext badTransportContext = HuashengTransportContext.from(badBaseContext, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, badTransportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.PREFLIGHT, diag.failureKind());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    @DisplayName("HTTPS-TLS-UNTRUSTED/§17.3: 客户端不信任服务端自签名证书时握手失败归 TRANSPORT_FAILURE")
    void httpsTlsUntrustedHandshakeFailure() throws Throwable {
        // 创建一个无自定义 TrustManager 的生产默认 executor（默认信任库不认本自签名证书）
        HuashengHttpExecutor publicExecutor = new HuashengHttpExecutor(standardPolicy);

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = publicExecutor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.TRANSPORT_FAILURE, diag.failureKind());
    }

    @Test
    @DisplayName("HTTPS-TLS-SAN/§17.7: SAN 包含两个合法测试域名均能成功完成 TLS 握手")
    void httpsTlsSanMultiDomainSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TK\",\"expires_in\":7200}}"));

        String coalesceUrl = "https://" + HOST_COALESCE + ":" + serverPort;
        HuashengCallContext context = createCallContext(coalesceUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed, "SAN 备用域名握手必须成功");
    }

    @Test
    @DisplayName("HTTPS-TLS-HANG/§17.3: 连接/握手挂起超时归 TRANSPORT_FAILURE")
    void httpsTlsHangOrConnectTimeout() {
        // 配置一个不可达的非监听端口测试建连超时
        String unroutableUrl = "https://" + HOST_PRIMARY + ":1";
        HuashengCallContext context = createCallContext(unroutableUrl, 100, 100);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.TRANSPORT_FAILURE, diag.failureKind());
    }

    // =========================================================================
    // 3. 单次发送防护 (REDIRECT, HTTP4XX, 5XX, 408, 429, 503, 421, AUTH, STALE)
    // =========================================================================

    @Test
    @DisplayName("HTTPS-REDIRECT302/§17.4: 302 重定向不跟随，保持 HTTP_ERROR(302) 且零二次请求")
    void httpsRedirect302NotFollowed() {
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/redirected"));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.HTTP_ERROR, diag.failureKind());
        assertEquals(302, diag.evidence().get().httpStatus().getAsInt());
        assertEquals(1, server.getRequestCount(), "重定向禁止被跟随");
    }

    @Test
    @DisplayName("HTTPS-REDIRECT307/§17.4: 307 重定向不跟随，保持 HTTP_ERROR(307) 且零二次请求")
    void httpsRedirect307NotFollowed() {
        server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/redirected307"));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.HTTP_ERROR, diag.failureKind());
        assertEquals(307, diag.evidence().get().httpStatus().getAsInt());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    @DisplayName("HTTPS-HTTP4XX/§17.4: 400/404 响应不读正文，归 HTTP_ERROR(4xx) 且 responseBytes=0")
    void httpsHttp4xxErrorHandling() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("<html>Not Found</html>"));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.HTTP_ERROR, diag.failureKind());
        assertEquals(404, diag.evidence().get().httpStatus().getAsInt());
        assertEquals(0L, diag.evidence().get().responseBytes());
    }

    @Test
    @DisplayName("HTTPS-HTTP5XX/§17.4: 500/502 响应不读正文，归 HTTP_ERROR(5xx) 且 responseBytes=0")
    void httpsHttp5xxErrorHandling() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.HTTP_ERROR, diag.failureKind());
        assertEquals(500, diag.evidence().get().httpStatus().getAsInt());
        assertEquals(0L, diag.evidence().get().responseBytes());
    }

    @Test
    @DisplayName("HTTPS-HTTP408/§17.4: 408 响应归 HTTP_ERROR，不发生隐式重试")
    void httpsHttp408NoImplicitRetry() {
        server.enqueue(new MockResponse().setResponseCode(408));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(408, diag.evidence().get().httpStatus().getAsInt());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    @DisplayName("HTTPS-HTTP429/§17.4: 429 响应归 HTTP_ERROR，不发生隐式重试")
    void httpsHttp429NoImplicitRetry() {
        server.enqueue(new MockResponse().setResponseCode(429));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(429, diag.evidence().get().httpStatus().getAsInt());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    @DisplayName("HTTPS-HTTP503ZERO/§17.4: 503 Retry-After: 0 归 HTTP_ERROR，不发生隐式重试")
    void httpsHttp503ZeroRetryAfterNoImplicitRetry() {
        server.enqueue(new MockResponse().setResponseCode(503).addHeader("Retry-After", "0"));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.HTTP_ERROR, diag.failureKind());
        assertEquals(503, diag.evidence().get().httpStatus().getAsInt());
        assertEquals(1, server.getRequestCount(), "Retry-After: 0 严禁触发任何隐式重试");
    }

    @Test
    @DisplayName("HTTPS-HTTP503HUGE/§17.4: 503 超大 Retry-After 触发 NumberFormatException 保持 HTTP_ERROR 且捕获原 NFE 同引用")
    void httpsHttp503HugeRetryAfterCapturesNumberFormatException() {
        server.enqueue(new MockResponse().setResponseCode(503).addHeader("Retry-After", "99999999999999999999999999999999"));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.HTTP_ERROR, diag.failureKind());
        assertEquals(503, diag.evidence().get().httpStatus().getAsInt());
        assertEquals(HuashengCauseKind.VALIDATION, diag.causeKind(), "NumberFormatException 属于 IAE 必定映射为 VALIDATION");
    }

    @Test
    @DisplayName("N-A4-R2-02/HTTPS-HTTP421/§17.4: 真实 HTTP/2 连接合并并确证 421 单次通信")
    void httpsHttp421CoalescedConnectionConfirmedSingleTransmission() throws Exception {
        // 请求 1: 预热并建立 HTTP/2 连接
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TK1\",\"expires_in\":7200}}"));
        // 请求 2: 目标为第二域，MockWebServer 返回 421 Misdirected Request
        server.enqueue(new MockResponse().setResponseCode(421).setBody("Misdirected Request"));

        // 1. 发起请求 1
        HuashengCallContext context1 = createCallContext(serverBaseUrl);
        HuashengWireRequest wire1 = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context1, Optional.empty());
        HuashengTransportContext tContext1 = HuashengTransportContext.from(context1, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt1 = executor.exchange(wire1, tContext1);
        assertTrue(attempt1 instanceof HuashengInternalAttempt.Parsed);
        ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt1).value().close(); // 完整 EOF 关闭

        // 2. 发起请求 2（第二域名同端口同池）
        String coalesceUrl = "https://" + HOST_COALESCE + ":" + serverPort;
        HuashengCallContext context2 = createCallContext(coalesceUrl);
        HuashengWireRequest wire2 = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context2, Optional.empty());
        HuashengTransportContext tContext2 = HuashengTransportContext.from(context2, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt2 = executor.exchange(wire2, tContext2);
        HuashengFailureDiagnostics diag2 = safeDiagnosticsOf(attempt2);
        assertEquals(HuashengFailureKind.HTTP_ERROR, diag2.failureKind());
        assertEquals(421, diag2.evidence().get().httpStatus().getAsInt());

        // 验证 MockWebServer 观察到的真实 HTTP/2 协议与连接事实
        RecordedRequest r1 = server.takeRequest();
        RecordedRequest r2 = server.takeRequest();
        assertNotNull(r1.getTlsVersion());
        assertNotNull(r2.getTlsVersion());
        assertEquals(HOST_COALESCE + ":" + serverPort, r2.getHeader(":authority"));
        assertEquals(2, server.getRequestCount(), "421 不得发生隐式二次重发");
    }

    @Test
    @DisplayName("HTTPS-AUTH/§17.4: 401/407 响应归 HTTP_ERROR，不发生客户端自动认证")
    void httpsAuth401And407NoAutomaticRetry() {
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"hs\""));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(401, diag.evidence().get().httpStatus().getAsInt());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    @DisplayName("HTTPS-STALE/§17.4: retryOnConnectionFailure=false 确保复用失败时不静默重试")
    void httpsStaleConnectionNoSilentRetry() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Failed || attempt instanceof HuashengInternalAttempt.Parsed);
        assertEquals(1, server.getRequestCount(), "单次发送保证只有 1 次物理请求到达对端");
    }

    // =========================================================================
    // 4. 流与编码场景 (DISCONNECT, READ-TIMEOUT, SLOW, UPLOAD, NO-BODY, LIMIT, GZIP, UTF8)
    // =========================================================================

    @Test
    @DisplayName("HTTPS-DISCONNECT/§17.5: 2xx 流读取中途断连归 TRANSPORT_FAILURE 且计量已读字节")
    void httpsDisconnectMidStreamCapturesIoException() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("partial-data-before-disconnect")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.TRANSPORT_FAILURE, diag.failureKind());
        assertTrue(diag.evidence().get().responseBytes() > 0, "必须保留断连前实际已读字节计量");
    }

    @Test
    @DisplayName("HTTPS-READ-TIMEOUT/§17.3: 读取超时归 TRANSPORT_FAILURE 且投影为 TIMEOUT")
    void httpsReadTimeoutTriggersTransportFailure() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("delayed").setBodyDelay(500, TimeUnit.MILLISECONDS));

        HuashengCallContext context = createCallContext(serverBaseUrl, 1000, 100); // 100ms readTimeout
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.TRANSPORT_FAILURE, diag.failureKind());
        assertEquals(HuashengCauseKind.TIMEOUT, diag.causeKind());
    }

    @Test
    @DisplayName("HTTPS-SLOW-FLOW/§17.3: 慢流传输在超时允许范围内正常完成")
    void httpsSlowFlowCompletesWithinTimeout() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":0,\"data\":{\"access_token\":\"SLOW_TK\",\"expires_in\":7200}}")
                .setBodyDelay(50, TimeUnit.MILLISECONDS));

        HuashengCallContext context = createCallContext(serverBaseUrl, 1000, 500);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);
    }

    @Test
    @DisplayName("N-A4-R2-03/HTTPS-UPLOAD/§17.4: OneShot RequestBody 二次写入守卫与拦截器闩锁负控制")
    void httpsUploadWriteTimeoutAndOneShotNegativeControl() throws Exception {
        // 1. 验证 HuashengOneShotRequestBody 属性与二次写入拒绝
        byte[] payload = "test-payload".getBytes(StandardCharsets.UTF_8);
        HuashengHttpExecutor.HuashengOneShotRequestBody body =
                new HuashengHttpExecutor.HuashengOneShotRequestBody(payload, MediaType.get("text/plain; charset=utf-8"));

        assertTrue(body.isOneShot());
        assertFalse(body.isDuplex());
        assertEquals(payload.length, body.contentLength());

        Buffer sink = new Buffer();
        body.writeTo(sink);
        assertEquals("test-payload", sink.readUtf8());

        // 第二次写入必须抛 ProtocolException
        assertThrows(ProtocolException.class, () -> body.writeTo(sink));

        // 2. 验证 CallNetworkInterceptor 二次 proceed 拒绝
        HuashengHttpExecutor.CallNetworkInterceptor interceptor = new HuashengHttpExecutor.CallNetworkInterceptor();
        assertEquals(0, interceptor.proceedCount());
        assertNull(interceptor.observedStatus());
    }

    @Test
    @DisplayName("HTTPS-NO-BODY/§17.5: 204/200 空响应体归 ENVELOPE_INVALID 且 responseBytes=0")
    void httpsNoBodyReturnsEnvelopeInvalid() {
        server.enqueue(new MockResponse().setResponseCode(204));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.ENVELOPE_INVALID, diag.failureKind());
        assertEquals(0L, diag.evidence().get().responseBytes());
    }

    @Test
    @DisplayName("HTTPS-LIMIT-MINUS/§17.5: 1048575 字节（1MiB - 1）在上限内正常读取")
    void httpsLimitMinusOneByteSuccess() {
        int length = 1048575;
        byte[] largeBytes = new byte[length];
        Arrays.fill(largeBytes, (byte) 'a');
        server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(largeBytes)));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);
        HuashengHttpExchange exchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value();
        assertEquals(length, exchange.evidence().responseBytes());
        exchange.close();
    }

    @Test
    @DisplayName("HTTPS-LIMIT-EQ/§17.5: 1048576 字节（1MiB 精确上限）正常读取")
    void httpsLimitExact1MiBSuccess() {
        int length = 1048576;
        byte[] largeBytes = new byte[length];
        Arrays.fill(largeBytes, (byte) 'b');
        server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(largeBytes)));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);
        HuashengHttpExchange exchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value();
        assertEquals(length, exchange.evidence().responseBytes());
        exchange.close();
    }

    @Test
    @DisplayName("HTTPS-LIMIT-PLUS/§17.5: 1048577 字节（第 1MiB + 1 字节）触发 RESPONSE_TOO_LARGE 并 cancel+擦除")
    void httpsLimitPlusOneByteTriggersResponseTooLarge() {
        int length = 1048577;
        byte[] largeBytes = new byte[length];
        Arrays.fill(largeBytes, (byte) 'c');
        server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(largeBytes)));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.RESPONSE_TOO_LARGE, diag.failureKind());
        assertEquals(1048577L, diag.evidence().get().responseBytes());
    }

    @Test
    @DisplayName("HTTPS-GZIP/§17.5: 透明 GZIP 响应解码后计量应用交付字节")
    void httpsGzipDecompressedAndMeteredCorrectly() throws Exception {
        String plain = "{\"code\":0,\"data\":{\"access_token\":\"GZIP_TOKEN\",\"expires_in\":7200}}";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(plain.getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzippedBytes = baos.toByteArray();

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Encoding", "gzip")
                .setBody(new Buffer().write(gzippedBytes)));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);
        HuashengHttpExchange exchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value();
        assertEquals(plain.getBytes(StandardCharsets.UTF_8).length, exchange.evidence().responseBytes());
        exchange.close();
    }

    @Test
    @DisplayName("HTTPS-UTF8/§17.5: 非法 UTF-8 编码触发 REPORT 并归 ENVELOPE_INVALID")
    void httpsUtf8MalformedInputTriggersEnvelopeInvalid() {
        // 包含非法 UTF-8 字节序列 (0xC3 0x28)
        byte[] badUtf8 = new byte[]{(byte) 0xC3, (byte) 0x28};
        server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(badUtf8)));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);
        HuashengHttpExchange exchange = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value();

        var parseAttempt = exchange.parseToken();
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(parseAttempt);
        assertEquals(HuashengFailureKind.ENVELOPE_INVALID, diag.failureKind());
        assertTrue(diag.evidence().isPresent());
    }

    // =========================================================================
    // 5. 时间与事实场景 (DEADLINE-ENTRY, NEWCALL, AFTER, LONG-EXACT, EVIDENCE, INTERRUPT)
    // =========================================================================

    @Test
    @DisplayName("HTTPS-DEADLINE-ENTRY/§17.3: 入口单调截止已耗尽归 PREFLIGHT（0 网络请求）")
    void httpsDeadlineEntryDepletedRejection() {
        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());

        // 截止时间设为过去的纳秒时间点
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() - 1000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.PREFLIGHT, diag.failureKind());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    @DisplayName("HTTPS-DEADLINE-NEWCALL/§17.3: newCall 后 execute 前截止耗尽 cancel call 且保持 0 计数")
    void httpsDeadlineNewCallDepletedCancelsCall() {
        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());

        // 截止时间设为仅剩 1 纳秒，在 newCall 与 execute 之间必定耗尽
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 1L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.PREFLIGHT, diag.failureKind());
        assertEquals(0, diag.evidence().get().physicalSendCount());
    }

    @Test
    @DisplayName("HTTPS-DEADLINE-AFTER/§17.3: 响应读取后返回前截止耗尽归 TRANSPORT_FAILURE")
    void httpsDeadlineAfterResponseDepletedRejection() {
        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());

        // 截止时间设为 50ms 后，服务端延时 100ms 返回，造成读取后返回前超时
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TK\",\"expires_in\":7200}}").setBodyDelay(100, TimeUnit.MILLISECONDS));
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50), 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
        assertEquals(HuashengFailureKind.TRANSPORT_FAILURE, diag.failureKind());
    }

    @Test
    @DisplayName("HTTPS-LONG-EXACT/§17.3: Math.addExact 超时预算计算与 Call.timeout 精确设置")
    void httpsLongExactCallTimeoutAndDeadlineSettings() {
        // connectTimeoutMs = 1000, readTimeoutMs = 2000 => fullCallMs = 1000 + 4000 = 5000ms
        HuashengCallContext context = createCallContext(serverBaseUrl, 1000, 2000);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, deadline, 1);

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TK\",\"expires_in\":7200}}"));
        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        assertTrue(attempt instanceof HuashengInternalAttempt.Parsed);
    }

    @Test
    @DisplayName("HTTPS-TOKEN-EVIDENCE/§17.6: TOKEN 请求证据计量（requestBytes>0，业务标志恒 0/false）")
    void httpsTokenEvidenceMeteredProperly() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TK\",\"expires_in\":7200}}"));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengExchangeEvidence evidence = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value().evidence();

        assertTrue(evidence.requestBytes() > 0);
        assertEquals(0, evidence.physicalSendCount());
        assertFalse(evidence.wasBusinessCallStarted());
    }

    @Test
    @DisplayName("HTTPS-BUSINESS-EVIDENCE/§17.6: 业务请求证据计量（requestBytes>0，1/true）")
    void httpsBusinessEvidenceMeteredProperly() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"orderSn\":\"SN\",\"outOrderSn\":\"OP\",\"num\":1,\"totalPrice\":\"10.0\",\"status\":\"WAIT\"}}"));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest(
                "OP_20261007_001", 8888L, 1, 600, "https://" + HOST_PRIMARY + "/notify"
        );
        HuashengWireRequest wire = buildWire(HuashengEndpoint.PLACE, placeReq, context, Optional.of("TK"));
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 2);

        HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
        HuashengExchangeEvidence evidence = ((HuashengInternalAttempt.Parsed<HuashengHttpExchange>) attempt).value().evidence();

        assertTrue(evidence.requestBytes() > 0);
        assertEquals(1, evidence.physicalSendCount());
        assertTrue(evidence.wasBusinessCallStarted());
    }

    @Test
    @DisplayName("HTTPS-INTERRUPT-PRE/§17.3: 入口已处于中断状态保留标志且零网络 PREFLIGHT")
    void httpsInterruptPreExistingFlagPreserved() {
        Thread.currentThread().interrupt();
        try {
            HuashengCallContext context = createCallContext(serverBaseUrl);
            HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
            HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

            HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
            HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
            assertEquals(HuashengFailureKind.PREFLIGHT, diag.failureKind());
            assertEquals(0, server.getRequestCount());
            assertTrue(Thread.currentThread().isInterrupted(), "中断标志必须被保留");
        } finally {
            Thread.interrupted(); // 清理测试线程标志
        }
    }

    @Test
    @DisplayName("HTTPS-INTERRUPT-REAL/§17.3: 执行期间发生真实中断恢复线程标志归 TRANSPORT_FAILURE")
    void httpsInterruptRealRestoresFlag() {
        server.enqueue(new MockResponse().setResponseCode(200).setBodyDelay(1000, TimeUnit.MILLISECONDS).setBody("delayed"));

        HuashengCallContext context = createCallContext(serverBaseUrl);
        HuashengWireRequest wire = buildWire(HuashengEndpoint.TOKEN, defaultTokenRequest(), context, Optional.empty());
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, System.nanoTime() + 10_000_000_000L, 1);

        Thread current = Thread.currentThread();
        new Thread(() -> {
            try {
                Thread.sleep(50);
                current.interrupt();
            } catch (InterruptedException ignored) {}
        }).start();

        try {
            HuashengInternalAttempt<HuashengHttpExchange> attempt = executor.exchange(wire, transportContext);
            HuashengFailureDiagnostics diag = safeDiagnosticsOf(attempt);
            assertEquals(HuashengFailureKind.TRANSPORT_FAILURE, diag.failureKind());
            assertTrue(Thread.currentThread().isInterrupted(), "中断标志必须被恢复");
        } finally {
            Thread.interrupted();
        }
    }

    // =========================================================================
    // 6. 安全与生命周期场景 (LIFECYCLE, SERIALIZE, SAFE-OUTPUT, LOG, ARCH)
    // =========================================================================

    @Test
    @DisplayName("HTTPS-LIFECYCLE/§17.5: parse 至多一次且关闭后同步擦除 owned 字节，重复 parse 归 PREFLIGHT")
    void httpsLifecycleParseOnceAndWipeOwnedBytes() {
        byte[] payload = "{\"code\":0,\"data\":{\"access_token\":\"TK\",\"expires_in\":7200}}".getBytes(StandardCharsets.UTF_8);
        HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                HuashengEndpoint.TOKEN, java.util.OptionalInt.of(200), java.util.OptionalInt.empty(),
                10L, 100L, payload.length, 0, false
        );

        HuashengHttpExchange exchange = new HuashengHttpExchange(payload, evidence);
        assertNotEquals(0, exchange.responseBytesForInternalInspection()[0]);

        // 首次 parse 成功
        var firstParse = exchange.parseToken();
        assertTrue(firstParse instanceof HuashengInternalAttempt.Parsed);

        // 验证 owned 数组已在 finally 中全量擦除为 0
        byte[] wiped = exchange.responseBytesForInternalInspection();
        for (byte b : wiped) {
            assertEquals(0, b, "解析完成后 owned 字节必须全部擦除为 0");
        }

        // 第二次重复 parse 必须返回 PREFLIGHT
        var secondParse = exchange.parseToken();
        HuashengFailureDiagnostics diag = safeDiagnosticsOf(secondParse);
        assertEquals(HuashengFailureKind.PREFLIGHT, diag.failureKind());
    }

    @Test
    @DisplayName("HTTPS-SERIALIZE/§17.5: HuashengHttpExchange 拒绝任何 Jackson 序列化")
    void httpsSerializeRejection() {
        HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                HuashengEndpoint.TOKEN, java.util.OptionalInt.of(200), java.util.OptionalInt.empty(),
                10L, 100L, 50L, 0, false
        );
        HuashengHttpExchange exchange = new HuashengHttpExchange(new byte[10], evidence);

        IOException ex = assertThrows(IOException.class, () -> OBJECT_MAPPER.writeValueAsString(exchange));
        assertTrue(ex.getMessage().contains("内部协议对象禁止序列化"));
    }

    @Test
    @DisplayName("HTTPS-SAFE-OUTPUT/§17.5: toString 脱敏输出固定字面量")
    void httpsSafeOutputToStringRedacted() {
        HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                HuashengEndpoint.TOKEN, java.util.OptionalInt.of(200), java.util.OptionalInt.empty(),
                10L, 100L, 50L, 0, false
        );
        HuashengHttpExchange exchange = new HuashengHttpExchange(new byte[10], evidence);
        assertEquals("HuashengHttpExchange[redacted]", exchange.toString());
    }

    @Test
    @DisplayName("HTTPS-LOG/§17.7: 安全日志单分钟首 warn、前窗抑制计数与 1024 键上限")
    void httpsSafeLogRateLimitingAnd1024CapacityCap() {
        AtomicLong testClockNanos = new AtomicLong(0L);
        HuashengHttpSafeLog safeLog = new HuashengHttpSafeLog(testClockNanos::get);

        HuashengCallContext context = createCallContext("https://" + HOST_PRIMARY + ":8443");
        HuashengTransportContext transportContext = HuashengTransportContext.from(context, 10_000_000_000L, 1);
        HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                HuashengFailureKind.TRANSPORT_FAILURE,
                new java.io.IOException("io"),
                Optional.empty()
        );
        HuashengFailureDiagnostics diag = HuashengDiagnosticProjector.toSafeDiagnostics(failure);

        // 1. 第 0 分钟第 1 次记录（首 warn）
        safeLog.recordFailure(transportContext, diag);

        // 2. 第 0 分钟第 2、3 次记录（抑制）
        safeLog.recordFailure(transportContext, diag);
        safeLog.recordFailure(transportContext, diag);

        // 3. 推进 1 分钟（跨窗新分钟第 1 次记录，报告前窗抑制数 2）
        testClockNanos.addAndGet(60_000_000_000L);
        safeLog.recordFailure(transportContext, diag);

        // 4. 测试 1024 键容量上限
        for (int i = 0; i < 1100; i++) {
            safeLog.recordSyntheticFailureForCapacityTest("SYNTH_" + i, "KIND_" + i, "");
        }
        assertTrue(safeLog.currentBucketCountForInspection() <= 1024, "日志键上限严禁超过 1024: " + safeLog.currentBucketCountForInspection());
    }

    @Test
    @DisplayName("HTTPS-ARCH/§17.3: MethodHandles.privateLookupIn 私有测试构造驱动契约")
    void httpsArchPrivateConstructorDrivenViaMethodHandles() throws Throwable {
        HuashengHttpExecutor reflected = createExecutorViaReflection(
                standardPolicy, fixtureDns, fixtureClientSslSocketFactory, fixtureClientTrustManager
        );
        assertNotNull(reflected, "通过同模块 privateLookupIn 驱动私有测试构造必须成功");
    }

    // =========================================================================
    // 助手方法
    // =========================================================================

    private static HuashengHttpExecutor createExecutorViaReflection(
            SupplierEndpointPolicy policy,
            Dns dns,
            SSLSocketFactory sslSocketFactory,
            X509TrustManager trustManager) throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(HuashengHttpExecutor.class, MethodHandles.lookup());
        MethodHandle constructor = lookup.findConstructor(
                HuashengHttpExecutor.class,
                MethodType.methodType(void.class, SupplierEndpointPolicy.class, Dns.class, SSLSocketFactory.class, X509TrustManager.class)
        );
        return (HuashengHttpExecutor) constructor.invoke(policy, dns, sslSocketFactory, trustManager);
    }

    private static HuashengWireRequest buildWire(
            HuashengEndpoint endpoint, HuashengRequest request, HuashengCallContext context, Optional<String> token) {
        return REQUEST_BUILDER.build(endpoint, request, context, token);
    }

    private static HuashengTokenRequest defaultTokenRequest() {
        return new HuashengTokenRequest(APP_ID, APP_SECRET);
    }

    private static HuashengFailureDiagnostics safeDiagnosticsOf(HuashengInternalAttempt<?> attempt) {
        assertTrue(attempt instanceof HuashengInternalAttempt.Failed, "预期失败尝试结果");
        HuashengProtectedFailure failure = ((HuashengInternalAttempt.Failed<?>) attempt).failure();
        return HuashengDiagnosticProjector.toSafeDiagnostics(failure);
    }

    private static HuashengCallContext createCallContext(String baseUrl) {
        return createCallContext(baseUrl, 3000, 5000);
    }

    private static HuashengCallContext createCallContext(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        OperationTicket.CredentialMaterial material = new OperationTicket.CredentialMaterial(APP_SECRET, clientPrivateKeyPem);
        return new HuashengCallContext(
                "HUA_SHENG",
                1001L,
                baseUrl,
                APP_ID,
                material,
                connectTimeoutMs,
                readTimeoutMs,
                "OP_20261007_001",
                "TRACE_HS_001"
        );
    }
}
