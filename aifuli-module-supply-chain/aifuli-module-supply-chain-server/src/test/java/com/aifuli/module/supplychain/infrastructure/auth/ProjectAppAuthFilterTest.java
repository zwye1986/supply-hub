package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthCredentials;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthSigner;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 入站项目应用验签过滤器单测：与 API Signer 对拍，覆盖有界 body、头基数与格式、注册表、
 * 时间窗口、凭证/HMAC/nonce 顺序、scope、可信上下文和所有退出路径的 ThreadLocal 清理。
 *
 * <p>详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#61-projectappauthfilter}。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProjectAppAuthFilterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String APP_ID = "app-filter-test";
    private static final String APP_KEY = "secret-key-for-filter-test";
    private static final String CHANNEL_CODE = "RT_MART";
    private static final String BUYER_USER_ID = "opaque-user-0000000000000001";
    private static final byte[] BODY = "{\"tradeId\":\"T-1\"}".getBytes(StandardCharsets.UTF_8);
    private static final ProjectAuthOperations.Operation CHANNEL_OPERATION = operation(
            "INSTORE_CREATE_PAYMENT_ORDER");
    private static final ProjectAuthOperations.Operation BUYER_OPERATION = operation("INSTORE_HOME_ENTRY");
    private static final ProjectAuthOperations.Operation PROJECT_OPERATION = operation(
            "INSTORE_CONFIRM_PAYMENT_CALLBACK");

    @Mock
    private ProjectAppCredentialService credentialService;
    @Mock
    private NonceStore nonceStore;

    private ProjectAppAuthProperties properties;
    private ProjectAppAuthFilter filter;

    @BeforeEach
    void setUp() {
        properties = new ProjectAppAuthProperties();
        properties.setMaxRequestBodyBytes(ProjectAppAuthProperties.HARD_MAX_REQUEST_BODY_BYTES);
        properties.setTimestampSkewMinutes(5);
        filter = new ProjectAppAuthFilter(credentialService, nonceStore, properties, OBJECT_MAPPER, CLOCK);
        lenient().when(credentialService.authenticate(org.mockito.ArgumentMatchers.eq(APP_ID),
                org.mockito.ArgumentMatchers.eq(1), anyString()))
                .thenReturn(new ProjectAppCredentialService.AuthenticatedApp(11L, 22L, APP_ID, 1, APP_KEY));
    }

    @AfterEach
    void tearDown() {
        ProjectAuthContextHolder.clear();
    }

    @Test
    void authenticatesChannelScopeAndLetsDownstreamReadBodyTwice() throws Exception {
        MockHttpServletRequest request = signedRequest(CHANNEL_OPERATION, channelCredentials(), BODY, NOW.toEpochMilli());
        request.removeHeader(ProjectAppAuthSigner.HEADER_BUYER_USER_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();

        runWithOldContext(request, response, (servletRequest, servletResponse) -> {
            ProjectAuthContext context = ProjectAuthContextHolder.get();
            assertThat(context).isNotNull();
            assertThat(context.scopeType()).isEqualTo(ProjectAuthOperations.ScopeType.CHANNEL_CODE);
            assertThat(context.channelCode()).isEqualTo(CHANNEL_CODE);
            assertThat(servletRequest.getInputStream().readAllBytes()).isEqualTo(BODY);
            assertThat(servletRequest.getInputStream().readAllBytes()).isEqualTo(BODY);
        });

        verify(credentialService).authenticate(APP_ID, 1, CHANNEL_OPERATION.operationCode());
        verify(credentialService).requireCapability(11L, "INSTORE");
        verify(nonceStore).ensureUnique(APP_ID, "nonce-filter-test");
        assertThat(ProjectAuthContextHolder.get()).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void authenticatesBuyerScopeWithoutConvertingOpaqueBuyerUserId() throws Exception {
        MockHttpServletRequest request = signedRequest(BUYER_OPERATION, buyerCredentials(), BODY, NOW.toEpochMilli());
        request.removeHeader(ProjectAppAuthSigner.HEADER_CHANNEL_CODE);

        runWithOldContext(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
            ProjectAuthContext context = ProjectAuthContextHolder.get();
            assertThat(context.scopeType()).isEqualTo(ProjectAuthOperations.ScopeType.BUYER_USER_ID);
            assertThat(context.buyerUserId()).isEqualTo(BUYER_USER_ID);
        });
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    @Test
    void authenticatesProjectScopeWhenBothOptionalHeadersAreMissing() throws Exception {
        MockHttpServletRequest request = signedRequest(PROJECT_OPERATION, projectCredentials(), BODY, NOW.toEpochMilli());
        request.removeHeader(ProjectAppAuthSigner.HEADER_BUYER_USER_ID);
        request.removeHeader(ProjectAppAuthSigner.HEADER_CHANNEL_CODE);

        runWithOldContext(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
            ProjectAuthContext context = ProjectAuthContextHolder.get();
            assertThat(context.scopeType()).isEqualTo(ProjectAuthOperations.ScopeType.NONE);
            assertThat(context.buyerUserId()).isNull();
            assertThat(context.channelCode()).isNull();
        });
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    @ParameterizedTest(name = "missing required header {0}")
    @MethodSource("requiredHeaders")
    void rejectsEachMissingCoreHeaderBeforeDatabase(String header) throws Exception {
        MockHttpServletRequest request = validChannelRequest();
        request.removeHeader(header);

        assertRejected(runRejectedWithOldContext(request));
        verifyNoInteractions(credentialService, nonceStore);
    }

    @ParameterizedTest(name = "duplicate auth header {0}")
    @MethodSource("allAuthHeaders")
    void rejectsDuplicateCoreOrOptionalHeaderEvenWhenValuesAreEqual(String header) throws Exception {
        MockHttpServletRequest request = validChannelRequest();
        String existing = request.getHeader(header);
        request.addHeader(header, existing == null ? "" : existing);

        assertRejected(runRejectedWithOldContext(request));
        verifyNoInteractions(credentialService, nonceStore);
    }

    @ParameterizedTest(name = "invalid formatted header {0}")
    @MethodSource("invalidHeaderValues")
    void rejectsMalformedOrOversizedHeaderBeforeDatabase(HeaderMutation mutation) throws Exception {
        MockHttpServletRequest request = validChannelRequest();
        replaceHeader(request, mutation.header(), mutation.value());

        assertRejected(runRejectedWithOldContext(request));
        verifyNoInteractions(credentialService, nonceStore);
    }

    @Test
    void rejectsUnknownPathMethodOperationAndContractBeforeDatabase() throws Exception {
        List<Consumer<MockHttpServletRequest>> mutations = List.of(
                request -> request.setRequestURI("/rpc-api/supply-chain/instore/channel-command/notRegistered"),
                request -> request.setMethod("GET"),
                request -> replaceHeader(request, ProjectAppAuthSigner.HEADER_OPERATION_CODE,
                        "INSTORE_QUERY_PAYMENT_STATUS"),
                request -> replaceHeader(request, ProjectAppAuthSigner.HEADER_CONTRACT_VERSION, "9.9.9"));

        for (Consumer<MockHttpServletRequest> mutation : mutations) {
            MockHttpServletRequest request = validChannelRequest();
            mutation.accept(request);
            assertRejected(runRejectedWithOldContext(request));
        }
        verifyNoInteractions(credentialService, nonceStore);
    }

    @Test
    void removesContextPathAndIgnoresQueryWhenMatchingAndVerifying() throws Exception {
        MockHttpServletRequest request = validChannelRequest();
        request.setContextPath("/supply");
        request.setRequestURI("/supply" + CHANNEL_OPERATION.path());
        request.setQueryString("ignored=true");

        runWithOldContext(request, new MockHttpServletResponse(), (servletRequest, servletResponse) ->
                assertThat(ProjectAuthContextHolder.get()).isNotNull());

        verify(nonceStore).ensureUnique(APP_ID, "nonce-filter-test");
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    @Test
    void acceptsTimestampAtBothInclusiveSkewBoundaries() throws Exception {
        long skewMillis = 5 * 60_000L;
        for (long timestamp : new long[]{NOW.toEpochMilli() - skewMillis, NOW.toEpochMilli() + skewMillis}) {
            MockHttpServletRequest request = signedRequest(CHANNEL_OPERATION, channelCredentials(), BODY, timestamp);
            runWithOldContext(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
            });
        }
        verify(credentialService, org.mockito.Mockito.times(2)).authenticate(APP_ID, 1, CHANNEL_OPERATION.operationCode());
        verify(nonceStore, org.mockito.Mockito.times(2)).ensureUnique(APP_ID, "nonce-filter-test");
    }

    @ParameterizedTest
    @MethodSource("timestampsOutsideWindow")
    void rejectsExpiredFutureAndLongExtremeTimestampBeforeDatabase(long timestamp) throws Exception {
        MockHttpServletRequest request = signedRequest(CHANNEL_OPERATION, channelCredentials(), BODY, timestamp);

        assertRejected(runRejectedWithOldContext(request));
        verifyNoInteractions(credentialService, nonceStore);
    }

    @Test
    void rejectsBodyOverConfiguredLimitBeforeHeaderOrDatabase() throws Exception {
        properties.setMaxRequestBodyBytes(8);
        filter = new ProjectAppAuthFilter(credentialService, nonceStore, properties, OBJECT_MAPPER, CLOCK);
        MockHttpServletRequest request = signedRequest(CHANNEL_OPERATION, channelCredentials(), new byte[9],
                NOW.toEpochMilli());

        assertRejected(runRejectedWithOldContext(request));
        verifyNoInteractions(credentialService, nonceStore);
    }

    @Test
    void rejectsTamperedBodyOrDeclaredHashBeforeNonce() throws Exception {
        MockHttpServletRequest tamperedBody = signedRequest(CHANNEL_OPERATION, channelCredentials(), BODY,
                NOW.toEpochMilli());
        tamperedBody.setContent("tampered".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest tamperedHash = validChannelRequest();
        replaceHeader(tamperedHash, ProjectAppAuthSigner.HEADER_REQUEST_SHA256, "0".repeat(64));

        assertRejected(runRejectedWithOldContext(tamperedBody));
        assertRejected(runRejectedWithOldContext(tamperedHash));
        verify(credentialService, org.mockito.Mockito.times(2)).authenticate(APP_ID, 1, CHANNEL_OPERATION.operationCode());
        verify(nonceStore, never()).ensureUnique(anyString(), anyString());
    }

    @Test
    void rejectsValidlyFormattedButIncorrectSignatureBeforeNonce() throws Exception {
        MockHttpServletRequest request = validChannelRequest();
        replaceHeader(request, ProjectAppAuthSigner.HEADER_SIGNATURE, "A".repeat(43));

        assertRejected(runRejectedWithOldContext(request));
        verify(credentialService).authenticate(APP_ID, 1, CHANNEL_OPERATION.operationCode());
        verifyNoInteractions(nonceStore);
    }

    @Test
    void credentialFailureReturnsUniformAuthErrorWithoutNonceWrite() throws Exception {
        ServiceException credentialFailure = exception(BUYER_AUTH_INVALID);
        when(credentialService.authenticate(APP_ID, 1, CHANNEL_OPERATION.operationCode())).thenThrow(credentialFailure);

        assertRejected(runRejectedWithOldContext(validChannelRequest()));
        verifyNoInteractions(nonceStore);
    }

    @Test
    void insertsNonceOnlyAfterCredentialAndValidSignatureAndMapsReplay() throws Exception {
        ServiceException replay = exception(BUYER_AUTH_INVALID);
        doThrow(replay).when(nonceStore).ensureUnique(APP_ID, "nonce-filter-test");

        assertRejected(runRejectedWithOldContext(validChannelRequest()));
        InOrder order = inOrder(credentialService, nonceStore);
        order.verify(credentialService).authenticate(APP_ID, 1, CHANNEL_OPERATION.operationCode());
        order.verify(nonceStore).ensureUnique(APP_ID, "nonce-filter-test");
    }

    @ParameterizedTest(name = "invalid scope {0}")
    @MethodSource("invalidScopes")
    void rejectsMissingOrExtraScopeOnlyAfterSignatureAndNonce(ScopeMutation mutation) throws Exception {
        MockHttpServletRequest request = signedRequest(mutation.operation(), mutation.credentials(), BODY,
                NOW.toEpochMilli());
        mutation.mutation().accept(request);
        resign(request, BODY);

        assertRejected(runRejectedWithOldContext(request));
        verify(nonceStore).ensureUnique(APP_ID, "nonce-filter-test");
    }

    @Test
    void mapsContextDefensiveIllegalArgumentExceptionToAuthInvalidWithCause() throws Exception {
        when(credentialService.authenticate(APP_ID, 1, CHANNEL_OPERATION.operationCode()))
                .thenReturn(new ProjectAppCredentialService.AuthenticatedApp(0L, 22L, APP_ID, 1, APP_KEY));
        CachedBodyHttpServletRequestWrapper request =
                new CachedBodyHttpServletRequestWrapper(validChannelRequest(), properties.getMaxRequestBodyBytes());

        assertThatThrownBy(() -> filter.authenticate(request))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", BUYER_AUTH_INVALID.getCode())
                .hasCauseInstanceOf(IllegalArgumentException.class);
        verify(nonceStore).ensureUnique(APP_ID, "nonce-filter-test");
    }

    @Test
    void contextConstructionFailureReturnsAuthErrorAndClearsPreexistingHolder() throws Exception {
        when(credentialService.authenticate(APP_ID, 1, CHANNEL_OPERATION.operationCode()))
                .thenReturn(new ProjectAppCredentialService.AuthenticatedApp(0L, 22L, APP_ID, 1, APP_KEY));

        assertRejected(runRejectedWithOldContext(validChannelRequest()));
        verify(nonceStore).ensureUnique(APP_ID, "nonce-filter-test");
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    @Test
    void requestStreamIoFailurePropagatesAndStillClearsPreexistingHolderBeforeDatabase() {
        IOException bodyFailure = new IOException("stream-failure");
        MockHttpServletRequest delegate = new MockHttpServletRequest("POST", CHANNEL_OPERATION.path());
        delegate.setRequestURI(CHANNEL_OPERATION.path());
        HttpServletRequest request = new HttpServletRequestWrapper(delegate) {
            @Override
            public ServletInputStream getInputStream() throws IOException {
                throw bodyFailure;
            }
        };
        ProjectAuthContextHolder.set(oldContext());

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> {
        })).isSameAs(bodyFailure);
        assertThat(ProjectAuthContextHolder.get()).isNull();
        verifyNoInteractions(credentialService, nonceStore);
    }

    @Test
    void clearsHolderAndPropagatesDownstreamBusinessServiceExceptionUnchanged() throws Exception {
        ServiceException downstream = new ServiceException(777, "downstream-business");
        MockHttpServletRequest request = validChannelRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ProjectAuthContextHolder.set(oldContext());
        assertThatThrownBy(() -> filter.doFilter(request, response,
                (servletRequest, servletResponse) -> { throw downstream; }))
                .isSameAs(downstream);
        assertThat(ProjectAuthContextHolder.get()).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void clearsHolderAndPropagatesDownstreamServletAndIoFailures() throws Exception {
        ServletException servletFailure = new ServletException("downstream-servlet");
        IOException ioFailure = new IOException("downstream-io");

        assertDownstreamFailureClearsHolder(servletFailure);
        assertDownstreamFailureClearsHolder(ioFailure);
    }

    @Test
    void clearsHolderEvenWhenWritingRejectionResponseFails() throws Exception {
        HttpServletResponse response = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override
            public PrintWriter getWriter() throws IOException {
                throw new IOException("response-write-failure");
            }
        };
        MockHttpServletRequest request = validChannelRequest();
        request.removeHeader(ProjectAppAuthSigner.HEADER_APP_ID);

        ProjectAuthContextHolder.set(oldContext());
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, resp) -> {
        })).isInstanceOf(IOException.class).hasMessage("response-write-failure");
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    @Test
    void rejectionUsesHttp200JsonCommonResultAndDoesNotInvokeChain() throws Exception {
        MockHttpServletRequest request = validChannelRequest();
        request.removeHeader(ProjectAppAuthSigner.HEADER_APP_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = org.mockito.Mockito.mock(FilterChain.class);

        ProjectAuthContextHolder.set(oldContext());
        filter.doFilter(request, response, chain);

        assertRejected(response);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verifyNoInteractions(chain);
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    @Test
    void committedResponseIsNotOverwrittenOnAuthenticationRejection() throws Exception {
        MockHttpServletRequest request = validChannelRequest();
        request.removeHeader(ProjectAppAuthSigner.HEADER_APP_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getWriter().write("already-committed");
        response.flushBuffer();

        runWithOldContext(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("认证失败不得进入下游");
        });

        assertThat(response.getContentAsString()).isEqualTo("already-committed");
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    private MockHttpServletResponse runRejectedWithOldContext(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        runWithOldContext(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("认证失败不得进入下游");
        });
        assertThat(ProjectAuthContextHolder.get()).isNull();
        return response;
    }

    @Test
    void filterPassesExactRegisteredStandardOperationToCredentialService() throws Exception {
        ProjectAuthOperations.Operation operation = ProjectAuthOperations
                .findByOperationCode(ProjectAuthOperations.STANDARD_ORDER_QUERY).orElseThrow();
        MockHttpServletRequest request = signedRequest(operation, projectCredentials(), BODY, NOW.toEpochMilli());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, resp) -> {
            assertThat(ProjectAuthContextHolder.get().operationCode()).isEqualTo(operation.operationCode());
        });
        verify(credentialService).authenticate(APP_ID, 1, operation.operationCode());
        verify(credentialService, never()).authenticate(APP_ID, 1);
        verify(credentialService).requireCapability(11L, ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT);
        verify(nonceStore).ensureUnique(APP_ID, "nonce-filter-test");
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    @ParameterizedTest
    @MethodSource("standardOperations")
    void standardCapabilityFailureAndInvalidSignatureCannotConsumeNonce(ProjectAuthOperations.Operation operation)
            throws Exception {
        MockHttpServletRequest invalidSignature = signedRequest(operation, projectCredentials(), BODY, NOW.toEpochMilli());
        invalidSignature.setContent("tampered".getBytes(StandardCharsets.UTF_8));
        assertRejected(runRejectedWithOldContext(invalidSignature));
        verify(credentialService, never()).requireCapability(org.mockito.ArgumentMatchers.any(), anyString());
        verifyNoInteractions(nonceStore);

        doThrow(exception(BUYER_AUTH_INVALID)).when(credentialService)
                .requireCapability(11L, ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT);
        assertRejected(runRejectedWithOldContext(signedRequest(operation, projectCredentials(), BODY, NOW.toEpochMilli())));
        verify(credentialService).requireCapability(11L, ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT);
        verifyNoInteractions(nonceStore);
    }

    @ParameterizedTest
    @MethodSource("standardOperations")
    void standardScopeDeclarationIsRejectedAfterNonceWithHolderCleared(ProjectAuthOperations.Operation operation)
            throws Exception {
        MockHttpServletRequest request = signedRequest(operation, projectCredentials(), BODY, NOW.toEpochMilli());
        request.addHeader(ProjectAppAuthSigner.HEADER_CHANNEL_CODE, CHANNEL_CODE);
        resign(request, BODY);
        assertRejected(runRejectedWithOldContext(request));
        verify(nonceStore).ensureUnique(APP_ID, "nonce-filter-test");
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    private static Stream<ProjectAuthOperations.Operation> standardOperations() {
        return ProjectAuthOperations.OPERATIONS.stream()
                .filter(op -> ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT.equals(op.requiredCapability()));
    }

    private void runWithOldContext(MockHttpServletRequest request, MockHttpServletResponse response,
                                   FilterChain chain) throws ServletException, IOException {
        ProjectAuthContextHolder.set(oldContext());
        filter.doFilter(request, response, chain);
    }

    private void assertDownstreamFailureClearsHolder(Exception failure) throws Exception {
        ProjectAuthContextHolder.set(oldContext());
        MockHttpServletRequest request = validChannelRequest();

        if (failure instanceof ServletException servletException) {
            assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                    (req, response) -> { throw servletException; })).isSameAs(servletException);
        } else {
            IOException ioException = (IOException) failure;
            assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                    (req, response) -> { throw ioException; })).isSameAs(ioException);
        }
        assertThat(ProjectAuthContextHolder.get()).isNull();
    }

    private static void assertRejected(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode json = OBJECT_MAPPER.readTree(response.getContentAsByteArray());
        assertThat(json.path("code").asInt()).isEqualTo(BUYER_AUTH_INVALID.getCode());
        assertThat(json.path("msg").asText()).isEqualTo(BUYER_AUTH_INVALID.getMsg());
        assertThat(json.path("data").isNull() || json.path("data").isMissingNode()).isTrue();
    }

    private MockHttpServletRequest validChannelRequest() {
        return signedRequest(CHANNEL_OPERATION, channelCredentials(), BODY, NOW.toEpochMilli());
    }

    private static MockHttpServletRequest signedRequest(ProjectAuthOperations.Operation operation,
                                                        ProjectAppAuthCredentials credentials,
                                                        byte[] body, long timestamp) {
        Map<String, String> headers = ProjectAppAuthSigner.sign(credentials, operation.httpMethod(), operation.path(),
                body, timestamp, "nonce-filter-test");
        MockHttpServletRequest request = new MockHttpServletRequest(operation.httpMethod(), operation.path());
        request.setRequestURI(operation.path());
        request.setContentType("application/json");
        request.setContent(body);
        headers.forEach(request::addHeader);
        return request;
    }

    private static void resign(MockHttpServletRequest request, byte[] body) {
        String canonical = ProjectAppAuthSigner.canonicalString(
                request.getHeader(ProjectAppAuthSigner.HEADER_APP_ID),
                request.getHeader(ProjectAppAuthSigner.HEADER_KEY_VERSION),
                request.getHeader(ProjectAppAuthSigner.HEADER_CONTRACT_VERSION),
                request.getHeader(ProjectAppAuthSigner.HEADER_OPERATION_CODE),
                request.getHeader(ProjectAppAuthSigner.HEADER_TIMESTAMP),
                request.getHeader(ProjectAppAuthSigner.HEADER_NONCE),
                ProjectAppAuthSigner.sha256Hex(body),
                normalized(request.getHeader(ProjectAppAuthSigner.HEADER_BUYER_USER_ID)),
                normalized(request.getHeader(ProjectAppAuthSigner.HEADER_CHANNEL_CODE)));
        replaceHeader(request, ProjectAppAuthSigner.HEADER_REQUEST_SHA256,
                ProjectAppAuthSigner.sha256Hex(body));
        replaceHeader(request, ProjectAppAuthSigner.HEADER_SIGNATURE,
                ProjectAppAuthSigner.hmacSha256Base64Url(APP_KEY, canonical));
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private static void replaceHeader(MockHttpServletRequest request, String header, String value) {
        request.removeHeader(header);
        request.addHeader(header, value);
    }

    private static ProjectAppAuthCredentials channelCredentials() {
        return new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, CHANNEL_CODE);
    }

    private static ProjectAppAuthCredentials buyerCredentials() {
        return new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, BUYER_USER_ID, null);
    }

    private static ProjectAppAuthCredentials projectCredentials() {
        return new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, null);
    }

    private static ProjectAuthContext oldContext() {
        return new ProjectAuthContext(101L, 102L, "old-app", 1,
                CHANNEL_OPERATION.operationCode(), null, "OLD_CHANNEL");
    }

    private static ProjectAuthOperations.Operation operation(String operationCode) {
        return ProjectAuthOperations.findByOperationCode(operationCode).orElseThrow();
    }

    private static Stream<String> requiredHeaders() {
        return Stream.of(
                ProjectAppAuthSigner.HEADER_APP_ID,
                ProjectAppAuthSigner.HEADER_KEY_VERSION,
                ProjectAppAuthSigner.HEADER_CONTRACT_VERSION,
                ProjectAppAuthSigner.HEADER_OPERATION_CODE,
                ProjectAppAuthSigner.HEADER_TIMESTAMP,
                ProjectAppAuthSigner.HEADER_NONCE,
                ProjectAppAuthSigner.HEADER_REQUEST_SHA256,
                ProjectAppAuthSigner.HEADER_SIGNATURE);
    }

    private static Stream<String> allAuthHeaders() {
        return Stream.concat(requiredHeaders(), Stream.of(
                ProjectAppAuthSigner.HEADER_BUYER_USER_ID,
                ProjectAppAuthSigner.HEADER_CHANNEL_CODE));
    }

    private static Stream<HeaderMutation> invalidHeaderValues() {
        return Stream.of(
                new HeaderMutation(ProjectAppAuthSigner.HEADER_APP_ID, "a".repeat(129)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_APP_ID, "bad\r\nvalue"),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_KEY_VERSION, "0"),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_KEY_VERSION, "-1"),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_KEY_VERSION, "2147483648"),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_OPERATION_CODE, "O".repeat(129)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_CONTRACT_VERSION, "1".repeat(33)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_TIMESTAMP, "+1"),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_TIMESTAMP, "1".repeat(21)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_NONCE, "n".repeat(129)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_NONCE, "nonce.with.dot"),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_REQUEST_SHA256, "A".repeat(64)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_REQUEST_SHA256, "0".repeat(63)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_SIGNATURE, "=".repeat(43)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_SIGNATURE, "A".repeat(44)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_BUYER_USER_ID, "u".repeat(129)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_BUYER_USER_ID, " ".repeat(129)),
                new HeaderMutation(ProjectAppAuthSigner.HEADER_CHANNEL_CODE, "c".repeat(33)));
    }

    private static Stream<Long> timestampsOutsideWindow() {
        long skewMillis = 5 * 60_000L;
        return Stream.of(
                NOW.toEpochMilli() - skewMillis - 1,
                NOW.toEpochMilli() + skewMillis + 1,
                Long.MIN_VALUE,
                Long.MAX_VALUE);
    }

    private static Stream<ScopeMutation> invalidScopes() {
        return Stream.of(
                new ScopeMutation("channel missing", CHANNEL_OPERATION, channelCredentials(),
                        request -> replaceHeader(request, ProjectAppAuthSigner.HEADER_CHANNEL_CODE, " ")),
                new ScopeMutation("channel has buyer", CHANNEL_OPERATION, channelCredentials(),
                        request -> replaceHeader(request, ProjectAppAuthSigner.HEADER_BUYER_USER_ID, BUYER_USER_ID)),
                new ScopeMutation("buyer missing", BUYER_OPERATION, buyerCredentials(),
                        request -> request.removeHeader(ProjectAppAuthSigner.HEADER_BUYER_USER_ID)),
                new ScopeMutation("buyer has channel", BUYER_OPERATION, buyerCredentials(),
                        request -> replaceHeader(request, ProjectAppAuthSigner.HEADER_CHANNEL_CODE, CHANNEL_CODE)),
                new ScopeMutation("none has buyer", PROJECT_OPERATION, projectCredentials(),
                        request -> replaceHeader(request, ProjectAppAuthSigner.HEADER_BUYER_USER_ID, BUYER_USER_ID)),
                new ScopeMutation("none has channel", PROJECT_OPERATION, projectCredentials(),
                        request -> replaceHeader(request, ProjectAppAuthSigner.HEADER_CHANNEL_CODE, CHANNEL_CODE)));
    }

    private record HeaderMutation(String header, String value) {
    }

    private record ScopeMutation(String name, ProjectAuthOperations.Operation operation,
                                 ProjectAppAuthCredentials credentials,
                                 Consumer<MockHttpServletRequest> mutation) {
        @Override
        public String toString() {
            return name;
        }
    }
}
