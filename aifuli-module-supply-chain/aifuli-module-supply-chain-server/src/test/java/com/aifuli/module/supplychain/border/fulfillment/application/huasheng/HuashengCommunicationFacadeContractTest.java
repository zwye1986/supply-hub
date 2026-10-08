package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCredentialIdentity.CredentialItemSnapshot;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.infrastructure.token.huasheng.HuashengInMemoryTokenCache;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengProtectedFailure;
import com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpExchange;
import com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpTransportPort;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengCallContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengConfirmRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExpectedOrder;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengPlaceRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengQueryRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengRequestBuilder;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeRequest;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTakeResponse;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTransportContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengWireRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇应用层通信门面契约测试（真实本地 MockWebServer HTTP 序列契约）。
 *
 * <p>职责：验证 {@link HuashengCommunicationFacade} 的完整调用编排链路：
 * <ul>
 *   <li>正常流程：TOKEN 获取 -> 业务外呼 -> 成功解析；</li>
 *   <li>有界 61 刷新：首次业务 61 -> 失效代次 -> 强制刷新 TOKEN -> 使用新时间戳/nonce 重发业务 -> 成功返回；</li>
 *   <li>二次 61 严格终止：至多 2 次业务 + 2 次 TOKEN 发送，绝无第 3 次外呼；</li>
 *   <li>业务发起前 TOKEN 失败抛 {@link HuashengProtocolException} 且 {@code wasBusinessCallStarted = false}；</li>
 *   <li>业务发起后刷新超时/预算耗尽返回 {@link HuashengCallResult.LocalFailure} 且 {@code wasBusinessCallStarted = true}；</li>
 *   <li>非 61 错误码（如 66）、非 2xx HTTP 状态码、网络超时均不重发。</li>
 * </ul>
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#184-有界-61-重试编排预算与调用门面ad-a5-06--ad-a5-08、§12.3。
 */
class HuashengCommunicationFacadeContractTest {

    private static String testPrivateKeyPem;
    private MockWebServer server;
    private String serverBaseUrl;
    private OkHttpClient httpClient;

    @BeforeAll
    static void setUpKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        testPrivateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(kp.getPrivate().getEncoded()) +
                "\n-----END PRIVATE KEY-----";
    }

    @BeforeEach
    void setUpServer() throws IOException {
        server = new MockWebServer();
        server.start();
        serverBaseUrl = server.url("/").toString();
        if (serverBaseUrl.endsWith("/")) {
            serverBaseUrl = serverBaseUrl.substring(0, serverBaseUrl.length() - 1);
        }
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();
    }

    @AfterEach
    void tearDownServer() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    private HuashengHttpTransportPort createRealWireTransportPort(AtomicInteger physicalBusinessSendCount) {
        return (wireReq, transportCtx) -> {
            boolean isBusiness = wireReq.endpoint() != HuashengEndpoint.TOKEN;
            int currentSendCount = 0;
            if (isBusiness) {
                currentSendCount = physicalBusinessSendCount.incrementAndGet();
            }

            Request.Builder reqBuilder = new Request.Builder()
                    .url(wireReq.url())
                    .post(RequestBody.create(wireReq.bodyHex(), MediaType.parse("text/plain; charset=utf-8")));

            wireReq.headers().forEach(reqBuilder::header);

            long start = System.currentTimeMillis();
            try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
                long duration = System.currentTimeMillis() - start;
                byte[] bodyBytes = response.body() != null ? response.body().bytes() : new byte[0];

                HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                        wireReq.endpoint(),
                        OptionalInt.of(response.code()),
                        OptionalInt.empty(),
                        duration,
                        wireReq.bodyHex().length(),
                        bodyBytes.length,
                        currentSendCount,
                        isBusiness
                );

                if (!response.isSuccessful()) {
                    return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                            HuashengFailureKind.HTTP_ERROR,
                            new IOException("HTTP " + response.code()),
                            Optional.of(evidence)
                    ));
                }

                Constructor<HuashengHttpExchange> ctor = HuashengHttpExchange.class.getDeclaredConstructor(byte[].class, HuashengExchangeEvidence.class);
                ctor.setAccessible(true);
                HuashengHttpExchange exchange = ctor.newInstance(bodyBytes, evidence);
                return HuashengInternalAttempt.parsed(exchange);
            } catch (SocketTimeoutException e) {
                long duration = System.currentTimeMillis() - start;
                HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                        wireReq.endpoint(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        duration,
                        wireReq.bodyHex().length(),
                        0L,
                        currentSendCount,
                        isBusiness
                );
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.TRANSPORT_FAILURE,
                        e,
                        Optional.of(evidence)
                ));
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                        wireReq.endpoint(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        duration,
                        wireReq.bodyHex().length(),
                        0L,
                        currentSendCount,
                        isBusiness
                );
                return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(
                        HuashengFailureKind.TRANSPORT_FAILURE,
                        e,
                        Optional.of(evidence)
                ));
            }
        };
    }

    private HuashengAuthorizedCallContext createCallContext(String suffix) {
        OperationTicket.CredentialMaterial material = new OperationTicket.CredentialMaterial(
                "1234567890123456", // 16 bytes
                testPrivateKeyPem
        );
        HuashengCallContext callContext = new HuashengCallContext(
                "HS_SUPPLIER_" + suffix,
                1001L,
                serverBaseUrl,
                "1234567890123456", // 16 bytes appId
                material,
                3000,
                5000,
                "OP_NO_" + suffix,
                "TRACE_" + suffix
        );
        HuashengCredentialIdentity credId = new HuashengCredentialIdentity(
                5001L,
                List.of(new CredentialItemSnapshot(1L, "key_" + suffix, "HS_TYPE", 1))
        );
        return new HuashengAuthorizedCallContext(callContext, credId);
    }

    private HuashengExpectedOrder createExpectedOrder(String outOrderSn) {
        return new HuashengExpectedOrder(outOrderSn, Optional.empty(), 1);
    }

    private HuashengExpectedOrder createExpectedOrderWithSn(String outOrderSn, String orderSn) {
        return new HuashengExpectedOrder(outOrderSn, Optional.of(orderSn), 1);
    }

    @Test
    @DisplayName("正常流程：TOKEN 获取 -> 业务 PLACE 下单 -> 成功解析，证据总数恰好为 2")
    void testHappyPathPlaceOrder() throws Exception {
        // Mock 1: TOKEN 响应
        String tokenBody = "{\"code\":0,\"data\":{\"access_token\":\"TEST_TOKEN_1\",\"expires_in\":7200}}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(tokenBody));

        // Mock 2: PLACE 响应
        String placeBody = "{\"code\":0,\"data\":{\"orderSn\":\"HS_ORDER_1\",\"outOrderSn\":\"OUT_001\",\"num\":1,\"totalPrice\":\"10.0000\",\"status\":\"WAIT\"}}";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(placeBody));

        AtomicInteger physicalSendCount = new AtomicInteger(0);
        HuashengHttpTransportPort transportPort = createRealWireTransportPort(physicalSendCount);
        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        Clock clock = Clock.systemUTC();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, clock, System::nanoTime);
        HuashengSafeLog safeLog = new HuashengSafeLog();

        HuashengCommunicationFacade facade = new HuashengCommunicationFacade(manager, builder, transportPort, clock, System::nanoTime, safeLog);

        HuashengAuthorizedCallContext ctx = createCallContext("happy");
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest("OUT_001", 100L, 1, 600, "https://notify.example.com");
        HuashengCallBudget budget = new HuashengCallBudget(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));

        HuashengCallResult<HuashengOrderResponse> result = facade.place(ctx, placeReq, createExpectedOrder("OUT_001"), budget);

        assertTrue(result instanceof HuashengCallResult.Parsed);
        HuashengOrderResponse orderResp = ((HuashengCallResult.Parsed<HuashengOrderResponse>) result).data();
        assertEquals("HS_ORDER_1", orderResp.orderSn());
        assertEquals("OUT_001", orderResp.outOrderSn());

        assertTrue(result.wasBusinessCallStarted());
        assertEquals(2, result.attempts().size(), "恰好 1 次 TOKEN + 1 次 PLACE 证据");
        assertEquals(1, physicalSendCount.get(), "业务物理发送次数恰好为 1");

        RecordedRequest req1 = server.takeRequest();
        assertTrue(req1.getPath().contains("/token"));
        RecordedRequest req2 = server.takeRequest();
        assertTrue(req2.getPath().contains("/order/place"));
        assertEquals("Bearer TEST_TOKEN_1", req2.getHeader("Authorization"));
    }

    @Test
    @DisplayName("有界 61 刷新重试：首次业务 61 -> 强制刷新 TOKEN -> 重发业务成功，恰好 2 次业务 + 2 次 TOKEN")
    void testBounded61RefreshSuccess() throws Exception {
        // Mock 1: 首次 TOKEN
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"OLD_TOKEN\",\"expires_in\":7200}}"));
        // Mock 2: 首次 PLACE 返回 61 TOKEN_UNAUTHORIZED
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":61,\"msg\":\"Token已过期\"}"));
        // Mock 3: 刷新 TOKEN
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"NEW_REFRESHED_TOKEN\",\"expires_in\":7200}}"));
        // Mock 4: 第二次 PLACE 成功
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"orderSn\":\"HS_ORDER_RETRY\",\"outOrderSn\":\"OUT_RETRY\",\"num\":1,\"totalPrice\":\"10.0000\",\"status\":\"WAIT\"}}"));

        AtomicInteger physicalSendCount = new AtomicInteger(0);
        HuashengHttpTransportPort transportPort = createRealWireTransportPort(physicalSendCount);
        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        Clock clock = Clock.systemUTC();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, clock, System::nanoTime);
        HuashengSafeLog safeLog = new HuashengSafeLog();

        HuashengCommunicationFacade facade = new HuashengCommunicationFacade(manager, builder, transportPort, clock, System::nanoTime, safeLog);

        HuashengAuthorizedCallContext ctx = createCallContext("retry61");
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest("OUT_RETRY", 100L, 1, 600, "https://notify.example.com");
        HuashengCallBudget budget = new HuashengCallBudget(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));

        HuashengCallResult<HuashengOrderResponse> result = facade.place(ctx, placeReq, createExpectedOrder("OUT_RETRY"), budget);

        assertTrue(result instanceof HuashengCallResult.Parsed);
        assertEquals("HS_ORDER_RETRY", ((HuashengCallResult.Parsed<HuashengOrderResponse>) result).data().orderSn());

        assertTrue(result.wasBusinessCallStarted());
        assertEquals(4, result.attempts().size(), "包含 2 次 TOKEN 与 2 次 业务发送证据");
        assertEquals(2, physicalSendCount.get(), "业务物理发送次数恰好为 2");

        // 验证 MockWebServer 接收序列
        RecordedRequest r1 = server.takeRequest();
        assertTrue(r1.getPath().contains("/token"));
        RecordedRequest r2 = server.takeRequest();
        assertTrue(r2.getPath().contains("/order/place"));
        assertEquals("Bearer OLD_TOKEN", r2.getHeader("Authorization"));

        RecordedRequest r3 = server.takeRequest();
        assertTrue(r3.getPath().contains("/token"));
        RecordedRequest r4 = server.takeRequest();
        assertTrue(r4.getPath().contains("/order/place"));
        assertEquals("Bearer NEW_REFRESHED_TOKEN", r4.getHeader("Authorization"));

        assertNotEquals(r2.getHeader("X-Sign"), r4.getHeader("X-Sign"), "第二次发送必须生成新签名与新时间戳/nonce");
        assertEquals(4, server.getRequestCount());
    }

    @Test
    @DisplayName("二次 61 严格终止：两次业务均返回 61 时，终止外呼并返回 BusinessError，绝无第三次发送")
    void testSecond61TerminatesWithBusinessError() throws Exception {
        // Mock 1: 首次 TOKEN
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TOK_1\",\"expires_in\":7200}}"));
        // Mock 2: 首次 PLACE 返回 61
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":61,\"msg\":\"Token已过期\"}"));
        // Mock 3: 刷新 TOKEN
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TOK_2\",\"expires_in\":7200}}"));
        // Mock 4: 第二次 PLACE 再次返回 61
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":61,\"msg\":\"Token再次无效\"}"));

        AtomicInteger physicalSendCount = new AtomicInteger(0);
        HuashengHttpTransportPort transportPort = createRealWireTransportPort(physicalSendCount);
        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        Clock clock = Clock.systemUTC();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, clock, System::nanoTime);
        HuashengSafeLog safeLog = new HuashengSafeLog();

        HuashengCommunicationFacade facade = new HuashengCommunicationFacade(manager, builder, transportPort, clock, System::nanoTime, safeLog);

        HuashengAuthorizedCallContext ctx = createCallContext("double61");
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest("OUT_DOUBLE", 100L, 1, 600, "https://notify.example.com");
        HuashengCallBudget budget = new HuashengCallBudget(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));

        HuashengCallResult<HuashengOrderResponse> result = facade.place(ctx, placeReq, createExpectedOrder("OUT_DOUBLE"), budget);

        assertTrue(result instanceof HuashengCallResult.BusinessError, "二次 61 必须终止并返回 BusinessError");
        HuashengCallResult.BusinessError<HuashengOrderResponse> bErr = (HuashengCallResult.BusinessError<HuashengOrderResponse>) result;
        assertEquals(61, bErr.error().rawCode());
        assertEquals(4, result.attempts().size());
        assertEquals(2, physicalSendCount.get(), "业务发送严格上限为 2");
        assertEquals(4, server.getRequestCount(), "服务总请求恰为 4");
    }

    @Test
    @DisplayName("业务发起前失败防护：首次 TOKEN 端点失败，抛 HuashengProtocolException 且 wasBusinessCallStarted=false")
    void testTokenCallErrorTerminatesBeforeBusinessCall() {
        // TOKEN 返回 4001 错误
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":4001,\"msg\":\"商户状态异常\"}"));

        AtomicInteger physicalSendCount = new AtomicInteger(0);
        HuashengHttpTransportPort transportPort = createRealWireTransportPort(physicalSendCount);
        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        Clock clock = Clock.systemUTC();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, clock, System::nanoTime);
        HuashengSafeLog safeLog = new HuashengSafeLog();

        HuashengCommunicationFacade facade = new HuashengCommunicationFacade(manager, builder, transportPort, clock, System::nanoTime, safeLog);

        HuashengAuthorizedCallContext ctx = createCallContext("tokenFail");
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest("OUT_TF", 100L, 1, 600, "https://notify.example.com");
        HuashengCallBudget budget = new HuashengCallBudget(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));

        HuashengProtocolException ex = assertThrows(HuashengProtocolException.class, () ->
                facade.place(ctx, placeReq, createExpectedOrder("OUT_TF"), budget));

        assertNotNull(ex.diagnostics());
        assertEquals(0, physicalSendCount.get(), "业务外呼绝对未发起");
        assertEquals(1, server.getRequestCount(), "仅发起了 1 次 TOKEN 请求");
    }

    @Test
    @DisplayName("非 61 业务错误（如 66 安全校验失败）：不触发重发，直接返回 BusinessError，恰好 1 次业务发送")
    void testNon61BusinessErrorNoRetry() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TOK_1\",\"expires_in\":7200}}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":66,\"msg\":\"安全校验失败\"}"));

        AtomicInteger physicalSendCount = new AtomicInteger(0);
        HuashengHttpTransportPort transportPort = createRealWireTransportPort(physicalSendCount);
        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        Clock clock = Clock.systemUTC();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, clock, System::nanoTime);
        HuashengSafeLog safeLog = new HuashengSafeLog();

        HuashengCommunicationFacade facade = new HuashengCommunicationFacade(manager, builder, transportPort, clock, System::nanoTime, safeLog);

        HuashengAuthorizedCallContext ctx = createCallContext("code66");
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest("OUT_66", 100L, 1, 600, "https://notify.example.com");
        HuashengCallBudget budget = new HuashengCallBudget(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));

        HuashengCallResult<HuashengOrderResponse> result = facade.place(ctx, placeReq, createExpectedOrder("OUT_66"), budget);

        assertTrue(result instanceof HuashengCallResult.BusinessError);
        assertEquals(66, ((HuashengCallResult.BusinessError<HuashengOrderResponse>) result).error().rawCode());
        assertEquals(1, physicalSendCount.get(), "非 61 业务拒绝绝不重发");
        assertEquals(2, result.attempts().size());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    @DisplayName("非 2xx HTTP 状态码（500）：不触发重试，直接返回 HttpFailure")
    void testNon2xxHttpErrorNoRetry() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TOK_1\",\"expires_in\":7200}}"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        AtomicInteger physicalSendCount = new AtomicInteger(0);
        HuashengHttpTransportPort transportPort = createRealWireTransportPort(physicalSendCount);
        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        Clock clock = Clock.systemUTC();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, clock, System::nanoTime);
        HuashengSafeLog safeLog = new HuashengSafeLog();

        HuashengCommunicationFacade facade = new HuashengCommunicationFacade(manager, builder, transportPort, clock, System::nanoTime, safeLog);

        HuashengAuthorizedCallContext ctx = createCallContext("http500");
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest("OUT_500", 100L, 1, 600, "https://notify.example.com");
        HuashengCallBudget budget = new HuashengCallBudget(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));

        HuashengCallResult<HuashengOrderResponse> result = facade.place(ctx, placeReq, createExpectedOrder("OUT_500"), budget);

        assertTrue(result instanceof HuashengCallResult.HttpFailure);
        assertEquals(1, physicalSendCount.get(), "HTTP 500 绝不重试");
        assertEquals(2, server.getRequestCount());
    }

    @Test
    @DisplayName("业务 61 后 Token 刷新超时：返回 LocalFailure 且 wasBusinessCallStarted=true，保留首轮发送证据")
    void testBusiness61ThenTokenRefreshTimeoutReturnsLocalFailure() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TOK_1\",\"expires_in\":7200}}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":61,\"msg\":\"Token已过期\"}"));
        // 第 2 次 TOKEN 触发 SocketTimeoutException (断开或无响应)
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        AtomicInteger physicalSendCount = new AtomicInteger(0);
        HuashengHttpTransportPort transportPort = createRealWireTransportPort(physicalSendCount);
        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        Clock clock = Clock.systemUTC();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, clock, System::nanoTime);
        HuashengSafeLog safeLog = new HuashengSafeLog();

        HuashengCommunicationFacade facade = new HuashengCommunicationFacade(manager, builder, transportPort, clock, System::nanoTime, safeLog);

        HuashengAuthorizedCallContext ctx = createCallContext("refreshTimeout");
        HuashengPlaceRequest placeReq = new HuashengPlaceRequest("OUT_TIMEOUT", 100L, 1, 600, "https://notify.example.com");
        HuashengCallBudget budget = new HuashengCallBudget(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));

        HuashengCallResult<HuashengOrderResponse> result = facade.place(ctx, placeReq, createExpectedOrder("OUT_TIMEOUT"), budget);

        assertTrue(result instanceof HuashengCallResult.LocalFailure, "业务发起后的本地刷新超时必须返回 LocalFailure");
        assertTrue(result.wasBusinessCallStarted(), "业务已发起，不能伪装为未执行");
        assertEquals(1, physicalSendCount.get());
        assertTrue(result.attempts().size() >= 2);
    }

    @Test
    @DisplayName("其他端点（CONFIRM / QUERY / TAKE）全覆盖验证")
    void testConfirmQueryTakeEndpoints() {
        AtomicInteger physicalSendCount = new AtomicInteger(0);
        HuashengHttpTransportPort transportPort = createRealWireTransportPort(physicalSendCount);
        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        Clock clock = Clock.systemUTC();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, clock, System::nanoTime);
        HuashengSafeLog safeLog = new HuashengSafeLog();

        HuashengCommunicationFacade facade = new HuashengCommunicationFacade(manager, builder, transportPort, clock, System::nanoTime, safeLog);

        // 1. CONFIRM
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"access_token\":\"TOK_CONFIRM\",\"expires_in\":7200}}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"orderSn\":\"HS_CONFIRM_1\",\"outOrderSn\":\"OUT_CONFIRM_1\",\"num\":1,\"totalPrice\":\"10.0000\",\"status\":\"SUCCESS\"}}"));

        HuashengAuthorizedCallContext ctx = createCallContext("confirm");
        HuashengConfirmRequest confirmReq = new HuashengConfirmRequest("HS_CONFIRM_1");
        HuashengCallBudget budget1 = new HuashengCallBudget(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
        HuashengCallResult<HuashengOrderResponse> confirmRes = facade.confirm(ctx, confirmReq, createExpectedOrderWithSn("OUT_CONFIRM_1", "HS_CONFIRM_1"), budget1);
        assertTrue(confirmRes instanceof HuashengCallResult.Parsed);

        // 2. QUERY
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"orderSn\":\"HS_QUERY_1\",\"outOrderSn\":\"OUT_QUERY_1\",\"num\":1,\"totalPrice\":\"10.0000\",\"status\":\"SUCCESS\",\"refundStatus\":\"NONE\",\"isTake\":\"N\"}}"));
        HuashengQueryRequest queryReq = HuashengQueryRequest.byOrderSn("HS_QUERY_1");
        HuashengCallBudget budget2 = new HuashengCallBudget(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
        HuashengCallResult<HuashengOrderResponse> queryRes = facade.query(ctx, queryReq, createExpectedOrderWithSn("OUT_QUERY_1", "HS_QUERY_1"), budget2);
        assertTrue(queryRes instanceof HuashengCallResult.Parsed);

        // 3. TAKE
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0,\"data\":{\"orderSn\":\"HS_TAKE_1\",\"num\":1,\"list\":[{\"type\":201,\"code\":\"CARD_CODE_1\",\"pass\":\"CARD_PASS_1\"}]}}"));
        HuashengTakeRequest takeReq = new HuashengTakeRequest("HS_TAKE_1");
        HuashengCallBudget budget3 = new HuashengCallBudget(2, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
        HuashengCallResult<HuashengTakeResponse> takeRes = facade.take(ctx, takeReq, createExpectedOrderWithSn("OUT_QUERY_1", "HS_TAKE_1"), budget3);
        assertTrue(takeRes instanceof HuashengCallResult.Parsed);
        assertEquals("HS_TAKE_1", ((HuashengCallResult.Parsed<HuashengTakeResponse>) takeRes).data().orderSn());
        assertEquals(1, ((HuashengCallResult.Parsed<HuashengTakeResponse>) takeRes).data().list().size());
    }
}
