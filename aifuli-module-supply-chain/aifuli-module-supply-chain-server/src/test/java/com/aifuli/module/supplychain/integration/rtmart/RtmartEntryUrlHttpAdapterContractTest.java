package com.aifuli.module.supplychain.integration.rtmart;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okio.Buffer;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 旅通 HTTP 边界的契约测试：以真实 OkHttp、MockWebServer 和运行时生成的 RSA 密钥验证线路协议，
 * 并防止响应或诊断路径泄漏凭证。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#4-旅通请求协议}、
 * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#5-响应与错误语义}
 * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#6-超时重试与事务}。</p>
 */
class RtmartEntryUrlHttpAdapterContractTest {

    private static final long PROJECT_ID = 143L;
    private static final String CHANNEL_CODE = "RT_MART";
    private static final String COMPANY_CODE = "企业_中文-01";
    private static final String APP_KEY = "app-key";
    private static final String TOKEN_SENTINEL = "TOKEN-SECRET-SENTINEL";
    private static final String PRIVATE_KEY_SENTINEL = "PRIVATE-KEY-SENTINEL";
    private static final String MESSAGE_SENTINEL = "MESSAGE-SECRET-SENTINEL";
    private static final String STRICT_BODY_SENTINEL = "STRICT-JSON-BODY-SENTINEL";
    private static final String SIGNER_SENTINEL = "SIGNER-FAILURE-SENTINEL";
    private static final String JUMP_URL_SENTINEL = "https://entry.example/path?token=JUMP-URL-SECRET-SENTINEL";
    private static final String CRLF_SENTINEL = "E4001\r\nX-Injected: CRLF-SENTINEL";
    private static final String FIXED_NONCE = "0123456789abcdef0123456789abcdef";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-24T01:02:03Z"), ZoneOffset.UTC);
    private static final String HOME_PATH = "/contract/home";
    private static final String DETAIL_PATH = "/contract/detail";

    private MockWebServer server;
    private KeyPair keyPair;
    private RtmartEntryUrlHttpAdapter adapter;
    private ListAppender<ILoggingEvent> appender;
    private Logger adapterLogger;
    private boolean adapterLoggerAdditive;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        adapter = newAdapter(Duration.ofMillis(200));
        adapterLogger = (Logger) LoggerFactory.getLogger(RtmartEntryUrlHttpAdapter.class);
        adapterLoggerAdditive = adapterLogger.isAdditive();
        adapterLogger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        adapterLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() throws Exception {
        adapterLogger.detachAppender(appender);
        adapterLogger.setAdditive(adapterLoggerAdditive);
        server.close();
    }

    @Test
    void homePostsExactJsonAndSignatureWhilePreservingChineseAndSpecialCharacters() throws Exception {
        server.enqueue(jsonSuccess(JUMP_URL_SENTINEL, ",\"future\":true"));

        String longitude = "120.12+中文&x";
        String latitude = "30.34=特殊 空格";
        assertThat(adapter.getHomeUrl(home(longitude, latitude)).entryUrl()).isEqualTo(JUMP_URL_SENTINEL);

        RecordedRequest request = server.takeRequest();
        Map<String, String> jsonFields = decodeJson(request.getBody().readUtf8());
        assertRequestHeaders(request, HOME_PATH);
        assertExactSignedJson(jsonFields, Map.of("app_key", APP_KEY, "company_code", COMPANY_CODE,
                "longitude", longitude, "latitude", latitude, "token", TOKEN_SENTINEL,
                "nonce_str", FIXED_NONCE, "timestamp", "1787533323"));
        verifySignature(jsonFields);
    }

    @Test
    void detailPostsTradeIdOnlySignedJsonAndVerifiableSignature() throws Exception {
        server.enqueue(jsonSuccess(JUMP_URL_SENTINEL, ""));
        adapter.getDetailUrl(detail());

        RecordedRequest request = server.takeRequest();
        Map<String, String> jsonFields = decodeJson(request.getBody().readUtf8());
        assertRequestHeaders(request, DETAIL_PATH);
        assertExactSignedJson(jsonFields, Map.of("app_key", APP_KEY, "company_code", COMPANY_CODE,
                "trade_id", "trade-id-中文&special", "token", TOKEN_SENTINEL,
                "nonce_str", FIXED_NONCE, "timestamp", "1787533323"));
        assertThat(jsonFields).doesNotContainKeys("orderNo", "order_no", "longitude", "latitude");
        verifySignature(jsonFields);
    }

    @Test
    void acceptsOnlyOfficialSuccessEnvelopeAndReturnsJumpUrlVerbatim() {
        server.enqueue(jsonSuccess(JUMP_URL_SENTINEL, ",\"url\":\"https://wrong.example\",\"data\":{\"url\":\"https://also-wrong.example\"}"));

        assertThat(adapter.getHomeUrl(home("120", "30")).entryUrl()).isEqualTo(JUMP_URL_SENTINEL);
    }

    @Test
    void rejectsTopLevelOrNestedUrlWithoutOfficialJumpUrl() {
        server.enqueue(new MockResponse().setBody("{\"code\":\"E0000\",\"result\":\"success\",\"url\":\"https://wrong.example\"}"));
        assertProtocol(() -> adapter.getHomeUrl(home("120", "30")));
        server.enqueue(new MockResponse().setBody("{\"code\":\"E0000\",\"result\":\"success\",\"data\":{\"url\":\"https://wrong.example\"}}"));
        assertProtocol(() -> adapter.getHomeUrl(home("120", "30")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"E4001", "E4002", "E4003", "E4004", "E5000", "E9999", "unknown"})
    void mapsEveryNonSuccessBusinessCodeToRemoteError(String code) {
        server.enqueue(new MockResponse().setBody("{\"code\":\"" + code + "\",\"result\":\"failed\",\"msg\":\"" + MESSAGE_SENTINEL + "\"}"));

        assertThatThrownBy(() -> adapter.getHomeUrl(home("120", "30")))
                .isInstanceOf(RtmartEntryException.class)
                .extracting(error -> ((RtmartEntryException) error).getReason())
                .isEqualTo(RtmartEntryException.Reason.REMOTE_ERROR);
    }

    @Test
    void rejectsMissingOrBlankRemoteCodeAsProtocolInsteadOfBusinessFailure() {
        server.enqueue(new MockResponse().setBody("{\"result\":\"success\",\"jumpUrl\":\"https://entry.example\"}"));
        assertProtocol(() -> adapter.getHomeUrl(home("120", "30")));
        server.enqueue(new MockResponse().setBody("{\"code\":\" \",\"result\":\"success\",\"jumpUrl\":\"https://entry.example\"}"));
        assertProtocol(() -> adapter.getHomeUrl(home("120", "30")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":\"E0000\",\"result\":\"failed\",\"jumpUrl\":\"https://entry.example\"}",
            "{\"code\":\"E0000\",\"result\":\"success\",\"jumpUrl\":\" \"}",
            "{\"code\":\"E0000\",\"result\":\"success\",\"jumpUrl\":\"http://entry.example\"}",
            "{\"code\":\"E0000\",\"result\":\"success\",\"jumpUrl\":\"/relative\"}",
            "{\"code\":\"E0000\",\"result\":\"success\",\"jumpUrl\":\"https://user@entry.example/path\"}",
            "{\"code\":\"E0000\",\"result\":\"success\",\"jumpUrl\":\"https://[bad\"}"
    })
    void rejectsContradictoryOrUnsafeOfficialSuccess(String body) {
        server.enqueue(new MockResponse().setBody(body));
        assertProtocol(() -> adapter.getHomeUrl(home("120", "30")));
    }

    @Test
    void derivesSafeClientEvenWhenInjectedClientUsesUnsafeDefaultRedirectPolicy() throws Exception {
        try (MockWebServer redirectTarget = new MockWebServer()) {
            redirectTarget.start();
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", redirectTarget.url("/next")).setBody("redirect"));

            assertThatThrownBy(() -> adapter.getHomeUrl(home("120", "30")))
                    .isInstanceOf(RtmartEntryException.class)
                    .extracting(error -> ((RtmartEntryException) error).getReason())
                    .isEqualTo(RtmartEntryException.Reason.HTTP_STATUS);

            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(redirectTarget.getRequestCount()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500})
    void mapsClientAndServerHttpStatusAndMakesExactlyOneRequest(int status) {
        server.enqueue(new MockResponse().setResponseCode(status).setBody("failure"));

        RtmartEntryException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> adapter.getHomeUrl(home("120", "30")), RtmartEntryException.class);

        assertThat(exception.getReason()).isEqualTo(RtmartEntryException.Reason.HTTP_STATUS);
        assertThat(exception.getHttpStatus()).isEqualTo(status);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void mapsEmptyBlankNonJsonAndSizeLimitToProtocol() {
        server.enqueue(new MockResponse().setBody(""));
        assertProtocol(() -> adapter.getHomeUrl(home("120", "30")));
        server.enqueue(new MockResponse().setBody("  \n\t"));
        assertProtocol(() -> adapter.getHomeUrl(home("120", "30")));
        server.enqueue(new MockResponse().setBody("null"));
        assertProtocol(() -> adapter.getHomeUrl(home("120", "30")));
        server.enqueue(new MockResponse().setBody("not-json-" + MESSAGE_SENTINEL));
        assertProtocol(() -> adapter.getHomeUrl(home("120", "30")));
        server.enqueue(new MockResponse().setBody("x".repeat(257)));
        assertProtocol(() -> newAdapter(Duration.ofMillis(200), 256).getHomeUrl(home("120", "30")));
        server.enqueue(new MockResponse().setChunkedBody("y".repeat(257), 16));
        assertProtocol(() -> newAdapter(Duration.ofMillis(200), 256).getHomeUrl(home("120", "30")));
    }

    @Test
    void rejectsStrictJsonViolationsAndNeverLeaksResponseBytes() {
        java.util.List<RtmartEntryException> failures = new java.util.ArrayList<>();
        server.enqueue(new MockResponse().setBody(successBody(JUMP_URL_SENTINEL) + STRICT_BODY_SENTINEL));
        failures.add(protocolFailure(() -> adapter.getHomeUrl(home("120", "30"))));
        server.enqueue(new MockResponse().setBody("{\"code\":\"E0000\",\"code\":\"E4001\",\"result\":\"success\",\"msg\":\""
                + STRICT_BODY_SENTINEL + "\",\"jumpUrl\":\"https://entry.example\"}"));
        failures.add(protocolFailure(() -> adapter.getHomeUrl(home("120", "30"))));
        server.enqueue(new MockResponse().setBody("{\"code\":\"E4001\",\"code\":\"E0000\",\"result\":\"success\",\"msg\":\""
                + STRICT_BODY_SENTINEL + "\",\"jumpUrl\":\"https://entry.example\"}"));
        failures.add(protocolFailure(() -> adapter.getHomeUrl(home("120", "30"))));
        for (String scalarCode : java.util.List.of("123", "true", "null")) {
            server.enqueue(new MockResponse().setBody("{\"code\":" + scalarCode + ",\"result\":\"success\",\"msg\":\""
                    + STRICT_BODY_SENTINEL + "\",\"jumpUrl\":\"https://entry.example\"}"));
            failures.add(protocolFailure(() -> adapter.getHomeUrl(home("120", "30"))));
        }
        Buffer invalidUtf8 = new Buffer().writeUtf8("{\"code\":\"E0000\",\"result\":\"success\",\"msg\":\""
                + STRICT_BODY_SENTINEL + "\",\"jumpUrl\":\"https://entry.example/").writeByte(0xc3).writeByte(0x28)
                .writeUtf8("\"}");
        server.enqueue(new MockResponse().setBody(invalidUtf8));
        failures.add(protocolFailure(() -> adapter.getHomeUrl(home("120", "30"))));

        String diagnostics = appender.list.stream().map(this::renderLoggingEvent).reduce("", String::concat)
                + failures.stream().map(RtmartEntryUrlHttpAdapterContractTest::renderThrowableTree).reduce("", String::concat);
        assertThat(diagnostics).doesNotContain(STRICT_BODY_SENTINEL, JUMP_URL_SENTINEL);
    }

    @Test
    void mapsReadTimeoutToTransportAndMakesExactlyOneRequest() {
        server.enqueue(new MockResponse().setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS)
                .setBody("{\"code\":\"E0000\",\"result\":\"success\",\"jumpUrl\":\"https://entry.example\"}"));
        assertThatThrownBy(() -> newAdapter(Duration.ofMillis(30)).getHomeUrl(home("120", "30")))
                .isInstanceOf(RtmartEntryException.class)
                .extracting(error -> ((RtmartEntryException) error).getReason())
                .isEqualTo(RtmartEntryException.Reason.TRANSPORT);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void parseAndJumpUrlFailuresNeverLeakSecretsThroughLogsOrThrowableTrees() throws Exception {
        String privateKeyEncoded = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String invalidChannel = "bad\r\nchannel-CRLF-SENTINEL";
        String invalidCompany = "bad\r\ncompany-CRLF-SENTINEL";
        RtmartEntryUrlPort.HomeUrlCommand command = new RtmartEntryUrlPort.HomeUrlCommand(PROJECT_ID, invalidChannel,
                invalidCompany, "120", "30", TOKEN_SENTINEL);
        server.enqueue(new MockResponse().setBody("not-json-" + TOKEN_SENTINEL + '-' + APP_KEY + '-' + privateKeyEncoded
                + '-' + JUMP_URL_SENTINEL + '-' + MESSAGE_SENTINEL + '-' + CRLF_SENTINEL));
        RtmartEntryException parseException = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> adapter.getHomeUrl(command), RtmartEntryException.class);
        String parseSign = extractSign(server.takeRequest());

        String invalidJumpUrl = "https://[bad?token=" + JUMP_URL_SENTINEL;
        server.enqueue(new MockResponse().setBody("{\"code\":\"E0000\",\"result\":\"success\",\"msg\":\""
                + jsonEscape(MESSAGE_SENTINEL + CRLF_SENTINEL) + "\",\"jumpUrl\":\"" + jsonEscape(invalidJumpUrl) + "\"}"));
        RtmartEntryException jumpUrlException = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> adapter.getHomeUrl(command), RtmartEntryException.class);
        String jumpUrlSign = extractSign(server.takeRequest());

        assertThat(parseException.getReason()).isEqualTo(RtmartEntryException.Reason.PROTOCOL);
        assertThat(jumpUrlException.getReason()).isEqualTo(RtmartEntryException.Reason.PROTOCOL);
        assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().contains("channelCode=INVALID")
                && event.getFormattedMessage().contains("companyCode=INVALID"));
        String diagnostics = appender.list.stream().map(this::renderLoggingEvent).reduce("", String::concat)
                + renderThrowableTree(parseException) + renderThrowableTree(jumpUrlException)
                + new RtmartEntryUrlPort.EntryUrlResult(JUMP_URL_SENTINEL);
        assertThat(diagnostics).doesNotContain(TOKEN_SENTINEL, PRIVATE_KEY_SENTINEL, privateKeyEncoded, APP_KEY, parseSign,
                jumpUrlSign, JUMP_URL_SENTINEL, MESSAGE_SENTINEL, "CRLF-SENTINEL", "sign=");
    }

    @Test
    void signerFailureWritesSingleSafeCompletionLogBeforeAnyHttpCall() {
        RtmartRequestSigner failingSigner = new RtmartRequestSigner(APP_KEY, keyPair.getPrivate(), FIXED_CLOCK, () -> {
            throw new IllegalStateException(SIGNER_SENTINEL);
        });
        RtmartEntryUrlHttpAdapter failingAdapter = newAdapter(Duration.ofMillis(200), 64 * 1024, failingSigner);

        RtmartEntryException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> failingAdapter.getHomeUrl(home("120", "30")), RtmartEntryException.class);

        assertThat(exception.getReason()).isEqualTo(RtmartEntryException.Reason.SIGNATURE);
        assertThat(server.getRequestCount()).isZero();
        assertThat(appender.list).hasSize(2);
        ILoggingEvent completion = appender.list.get(1);
        assertThat(completion.getFormattedMessage()).contains("operation=HOME", "projectId=" + PROJECT_ID,
                "channelCode=" + CHANNEL_CODE, "companyCode=INVALID", "reason=SIGNATURE", "httpStatus=null",
                "remoteCode=null", "elapsedMs=");
        assertThat(renderLoggingEvent(completion)).doesNotContain(SIGNER_SENTINEL, TOKEN_SENTINEL, APP_KEY,
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
    }

    @Test
    void responseEnvelopeSummaryRedactsRemoteMessageAndJumpUrl() {
        RtmartEntryResponse response = new RtmartEntryResponse(MESSAGE_SENTINEL, "success", "E0000", JUMP_URL_SENTINEL);

        assertThat(response.toString()).doesNotContain(MESSAGE_SENTINEL, JUMP_URL_SENTINEL, "JUMP-URL-SECRET-SENTINEL");
    }

    private RtmartEntryUrlHttpAdapter newAdapter(Duration readTimeout) {
        return newAdapter(readTimeout, 64 * 1024);
    }

    private RtmartEntryUrlHttpAdapter newAdapter(Duration readTimeout, int maxResponseBytes) {
        RtmartRequestSigner signer = new RtmartRequestSigner(APP_KEY, keyPair.getPrivate(), FIXED_CLOCK, () -> FIXED_NONCE);
        return newAdapter(readTimeout, maxResponseBytes, signer);
    }

    private RtmartEntryUrlHttpAdapter newAdapter(Duration readTimeout, int maxResponseBytes, RtmartRequestSigner signer) {
        RtmartEntryProperties properties = new RtmartEntryProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setHomePath(HOME_PATH);
        properties.setDetailPath(DETAIL_PATH);
        properties.setMaxResponseBytes(maxResponseBytes);
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(readTimeout).build();
        return new RtmartEntryUrlHttpAdapter(properties, client, new ObjectMapper(), signer);
    }

    private RtmartEntryUrlPort.HomeUrlCommand home(String longitude, String latitude) {
        return new RtmartEntryUrlPort.HomeUrlCommand(PROJECT_ID, CHANNEL_CODE, COMPANY_CODE, longitude, latitude, TOKEN_SENTINEL);
    }

    private RtmartEntryUrlPort.DetailUrlCommand detail() {
        return new RtmartEntryUrlPort.DetailUrlCommand(PROJECT_ID, CHANNEL_CODE, COMPANY_CODE, "trade-id-中文&special", TOKEN_SENTINEL);
    }

    private MockResponse jsonSuccess(String jumpUrl, String extraFields) {
        return new MockResponse().addHeader("Content-Type", "application/json")
                .setBody(successBody(jumpUrl, extraFields));
    }

    private static String successBody(String jumpUrl) {
        return successBody(jumpUrl, "");
    }

    private static String successBody(String jumpUrl, String extraFields) {
        return "{\"msg\":\"\",\"result\":\"success\",\"code\":\"E0000\",\"jumpUrl\":\"" + jumpUrl + "\"" + extraFields + "}";
    }

    private void verifySignature(Map<String, String> jsonFields) throws Exception {
        Map<String, String> signed = new LinkedHashMap<>(jsonFields);
        String signature = signed.remove("sign");
        String canonical = signed.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + entry.getValue()).reduce("", String::concat);
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(signature))).isTrue();
    }

    private static void assertRequestHeaders(RecordedRequest request, String path) {
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo(path);
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json; charset=UTF-8");
    }

    private static void assertExactSignedJson(Map<String, String> jsonFields, Map<String, String> expectedFields) {
        String signature = jsonFields.remove("sign");
        assertThat(signature).isNotBlank();
        assertThat(jsonFields).isEqualTo(expectedFields);
        jsonFields.put("sign", signature);
    }

    private static String extractSign(RecordedRequest request) {
        String sign = decodeJson(request.getBody().readUtf8()).get("sign");
        assertThat(sign).isNotBlank();
        return sign;
    }

    private String renderLoggingEvent(ILoggingEvent event) {
        String arguments = java.util.Arrays.stream(event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray())
                .map(String::valueOf).reduce("", String::concat);
        return event.getFormattedMessage() + arguments + renderThrowableProxy(event.getThrowableProxy());
    }

    private static String renderThrowableProxy(IThrowableProxy proxy) {
        if (proxy == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(proxy.getClassName()).append(proxy.getMessage());
        for (var stackTraceElement : proxy.getStackTraceElementProxyArray()) {
            text.append(stackTraceElement);
        }
        for (IThrowableProxy suppressed : proxy.getSuppressed()) {
            text.append(renderThrowableProxy(suppressed));
        }
        return text.append(renderThrowableProxy(proxy.getCause())).toString();
    }

    private static String renderThrowableTree(Throwable throwable) {
        return renderThrowableTree(throwable, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static String renderThrowableTree(Throwable throwable, Set<Throwable> visited) {
        if (throwable == null || !visited.add(throwable)) {
            return "";
        }
        StringWriter trace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(trace));
        StringBuilder text = new StringBuilder(throwable.toString()).append(trace);
        for (Throwable suppressed : throwable.getSuppressed()) {
            text.append(renderThrowableTree(suppressed, visited));
        }
        return text.append(renderThrowableTree(throwable.getCause(), visited)).toString();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static Map<String, String> decodeJson(String body) {
        try {
            return JSON_MAPPER.readValue(body, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new AssertionError("Failed to parse JSON body: " + e.getMessage(), e);
        }
    }

    private static void assertProtocol(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        protocolFailure(action);
    }

    private static RtmartEntryException protocolFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        RtmartEntryException exception = org.assertj.core.api.Assertions.catchThrowableOfType(action, RtmartEntryException.class);
        assertThat(exception.getReason()).isEqualTo(RtmartEntryException.Reason.PROTOCOL);
        return exception;
    }
}
