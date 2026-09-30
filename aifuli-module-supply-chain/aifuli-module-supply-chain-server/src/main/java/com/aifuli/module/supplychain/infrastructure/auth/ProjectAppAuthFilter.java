package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.auth.ProjectAppAuthSigner;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Enumeration;
import java.util.Objects;
import java.util.regex.Pattern;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;

/**
 * 项目应用统一认证入站过滤器：有界 body → 头与注册表 → 时间窗 → 凭证 → 摘要/HMAC → nonce →
 * scope → 可信上下文，并在覆盖全部退出路径的 {@code finally} 中清理线程上下文。
 *
 * <p>认证阶段的可预期失败统一返回 HTTP 200 + {@code BUYER_AUTH_INVALID}；下游业务异常不在本类
 * 捕获，原样交给后续异常边界。高频拒绝只记录不含凭据、nonce、签名、body 或 scope 值的 DEBUG
 * 摘要。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#61-projectappauthfilter}。</p>
 */
@Slf4j
public final class ProjectAppAuthFilter extends OncePerRequestFilter {

    private static final int APP_ID_MAX_LENGTH = 128;
    private static final int KEY_VERSION_MAX_LENGTH = 10;
    private static final int CONTRACT_VERSION_MAX_LENGTH = 32;
    private static final int OPERATION_CODE_MAX_LENGTH = 128;
    private static final int TIMESTAMP_MAX_LENGTH = 20;
    private static final int NONCE_MAX_LENGTH = 128;
    private static final int BUYER_USER_ID_MAX_LENGTH = 128;
    private static final int CHANNEL_CODE_MAX_LENGTH = 32;

    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
    private static final Pattern SIGNED_DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern NONCE_BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern SHA256_LOWER_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern HMAC_BASE64_URL = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final ProjectAppCredentialService credentialService;
    private final NonceStore nonceStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maximumBodyBytes;
    private final long timestampSkewMillis;

    /** 生产入口固定 UTC 系统时钟；包级重载供时间边界测试注入固定时钟。 */
    @Autowired
    public ProjectAppAuthFilter(ProjectAppCredentialService credentialService,
                                NonceStore nonceStore,
                                ProjectAppAuthProperties properties,
                                ObjectMapper objectMapper) {
        this(credentialService, nonceStore, properties, objectMapper, Clock.systemUTC());
    }

    ProjectAppAuthFilter(ProjectAppCredentialService credentialService,
                         NonceStore nonceStore,
                         ProjectAppAuthProperties properties,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.nonceStore = Objects.requireNonNull(nonceStore, "nonceStore");
        Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        validateBodyLimit(properties.getMaxRequestBodyBytes());
        this.maximumBodyBytes = properties.getMaxRequestBodyBytes();
        this.timestampSkewMillis = resolveTimestampSkewMillis(properties.getTimestampSkewMinutes());
    }

    /**
     * 最外层先清旧值、最后 remove；认证 ServiceException 的捕获范围在下游调用之前结束，避免把
     * controller/facade 的业务异常误装成认证失败。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ProjectAuthContextHolder.clear();
        try {
            CachedBodyHttpServletRequestWrapper wrapped;
            ProjectAuthContext context;
            try {
                wrapped = new CachedBodyHttpServletRequestWrapper(request, maximumBodyBytes);
                context = authenticate(wrapped);
            } catch (CachedBodyHttpServletRequestWrapper.BodyTooLargeException exception) {
                reject(response);
                return;
            } catch (ServiceException exception) {
                reject(response);
                return;
            }
            ProjectAuthContextHolder.set(context);
            filterChain.doFilter(wrapped, response);
        } finally {
            ProjectAuthContextHolder.clear();
        }
    }

    /** 固定认证顺序；包级可见仅用于证明上下文防御性异常保留 cause。 */
    ProjectAuthContext authenticate(CachedBodyHttpServletRequestWrapper request) {
        ParsedHeaders headers = parseHeaders(request);
        String path = pathWithoutContext(request);
        ProjectAuthOperations.Operation operation = ProjectAuthOperations
                .findByMethodAndPath(request.getMethod(), path)
                .orElseThrow(ProjectAppAuthFilter::authInvalid);
        if (!operation.operationCode().equals(headers.operationCode())
                || !operation.contractVersion().equals(headers.contractVersion())) {
            throw authInvalid();
        }
        log.debug("[project-auth] 请求认证入口 method={}, operationCode={}, appId={}, keyVersion={}",
                request.getMethod(), operation.operationCode(), headers.appId(), headers.keyVersion());

        validateTimestamp(headers.timestamp());
        ProjectAppCredentialService.AuthenticatedApp app =
                credentialService.authenticate(headers.appId(), headers.keyVersion());
        validateAuthenticatedSnapshot(app, headers);

        String actualHash = ProjectAppAuthSigner.sha256Hex(request.bodyForDigest());
        if (!constantTimeEquals(actualHash, headers.requestSha256())) {
            throw authInvalid();
        }
        String canonical = ProjectAppAuthSigner.canonicalString(
                headers.appId(), Integer.toString(headers.keyVersion()), headers.contractVersion(),
                headers.operationCode(), Long.toString(headers.timestamp()), headers.nonce(),
                headers.requestSha256(), nullToEmpty(headers.buyerUserId()), nullToEmpty(headers.channelCode()));
        final String expectedSignature;
        try {
            expectedSignature = ProjectAppAuthSigner.hmacSha256Base64Url(app.appKey(), canonical);
        } catch (IllegalStateException exception) {
            throw authInvalid(exception);
        }
        if (!constantTimeEquals(expectedSignature, headers.signature())) {
            throw authInvalid();
        }

        nonceStore.ensureUnique(headers.appId(), headers.nonce());
        validateScope(operation.scopeType(), headers.buyerUserId(), headers.channelCode());
        try {
            return new ProjectAuthContext(app.projectId(), app.buyerId(), app.appId(), app.keyVersion(),
                    operation.operationCode(), headers.buyerUserId(), headers.channelCode());
        } catch (IllegalArgumentException exception) {
            throw authInvalid(exception);
        }
    }

    /** enabled Filter 创建时校验 1..1 MiB，防止错误配置拖到首请求。 */
    static void validateBodyLimit(int limit) {
        if (limit < 1 || limit > ProjectAppAuthProperties.HARD_MAX_REQUEST_BODY_BYTES) {
            throw new IllegalStateException("aifuli.supply-chain.auth.max-request-body-bytes 必须位于 1..1048576");
        }
    }

    /** enabled Filter 创建时校验正数与精确毫秒换算。 */
    static long resolveTimestampSkewMillis(long skewMinutes) {
        if (skewMinutes <= 0) {
            throw new IllegalStateException("aifuli.supply-chain.auth.timestamp-skew-minutes 必须为正数");
        }
        try {
            return Math.multiplyExact(skewMinutes, 60_000L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "aifuli.supply-chain.auth.timestamp-skew-minutes 超出毫秒可表示范围", exception);
        }
    }

    private ParsedHeaders parseHeaders(HttpServletRequest request) {
        String appId = coreHeader(request, ProjectAppAuthSigner.HEADER_APP_ID, APP_ID_MAX_LENGTH);
        String rawKeyVersion = coreHeader(
                request, ProjectAppAuthSigner.HEADER_KEY_VERSION, KEY_VERSION_MAX_LENGTH);
        String contractVersion = coreHeader(
                request, ProjectAppAuthSigner.HEADER_CONTRACT_VERSION, CONTRACT_VERSION_MAX_LENGTH);
        String operationCode = coreHeader(
                request, ProjectAppAuthSigner.HEADER_OPERATION_CODE, OPERATION_CODE_MAX_LENGTH);
        String rawTimestamp = coreHeader(
                request, ProjectAppAuthSigner.HEADER_TIMESTAMP, TIMESTAMP_MAX_LENGTH);
        String nonce = coreHeader(request, ProjectAppAuthSigner.HEADER_NONCE, NONCE_MAX_LENGTH);
        String requestSha256 = coreHeader(request, ProjectAppAuthSigner.HEADER_REQUEST_SHA256, 64);
        String signature = coreHeader(request, ProjectAppAuthSigner.HEADER_SIGNATURE, 43);
        String buyerUserId = optionalHeader(
                request, ProjectAppAuthSigner.HEADER_BUYER_USER_ID, BUYER_USER_ID_MAX_LENGTH);
        String channelCode = optionalHeader(
                request, ProjectAppAuthSigner.HEADER_CHANNEL_CODE, CHANNEL_CODE_MAX_LENGTH);

        if (!POSITIVE_DECIMAL.matcher(rawKeyVersion).matches()
                || !SIGNED_DECIMAL.matcher(rawTimestamp).matches()
                || !NONCE_BASE64_URL.matcher(nonce).matches()
                || !SHA256_LOWER_HEX.matcher(requestSha256).matches()
                || !HMAC_BASE64_URL.matcher(signature).matches()) {
            throw authInvalid();
        }
        final int keyVersion;
        final long timestamp;
        try {
            keyVersion = Integer.parseInt(rawKeyVersion);
            timestamp = Long.parseLong(rawTimestamp);
        } catch (NumberFormatException exception) {
            throw authInvalid(exception);
        }
        return new ParsedHeaders(appId, keyVersion, contractVersion, operationCode, timestamp,
                nonce, requestSha256, signature, buyerUserId, channelCode);
    }

    private void validateTimestamp(long timestamp) {
        try {
            long delta = Math.subtractExact(timestamp, clock.millis());
            if (delta < -timestampSkewMillis || delta > timestampSkewMillis) {
                throw authInvalid();
            }
        } catch (ArithmeticException exception) {
            throw authInvalid(exception);
        }
    }

    private static void validateAuthenticatedSnapshot(ProjectAppCredentialService.AuthenticatedApp app,
                                                      ParsedHeaders headers) {
        if (app == null || !headers.appId().equals(app.appId())
                || headers.keyVersion() != app.keyVersion() || app.appKey() == null || app.appKey().isBlank()) {
            throw authInvalid();
        }
    }

    private static void validateScope(ProjectAuthOperations.ScopeType scope,
                                      String buyerUserId, String channelCode) {
        boolean buyerPresent = buyerUserId != null;
        boolean channelPresent = channelCode != null;
        boolean valid = switch (scope) {
            case BUYER_USER_ID -> buyerPresent && !channelPresent;
            case CHANNEL_CODE -> !buyerPresent && channelPresent;
            case NONE -> !buyerPresent && !channelPresent;
        };
        if (!valid) {
            throw authInvalid();
        }
    }

    private static String pathWithoutContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null || contextPath == null || !uri.startsWith(contextPath)) {
            throw authInvalid();
        }
        return uri.substring(contextPath.length());
    }

    private static String coreHeader(HttpServletRequest request, String name, int maximumLength) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            throw authInvalid();
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()) {
            throw authInvalid();
        }
        validateHeaderValue(value, maximumLength);
        return value;
    }

    private static String optionalHeader(HttpServletRequest request, String name, int maximumLength) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        if (values.hasMoreElements()) {
            throw authInvalid();
        }
        if (value == null) {
            return null;
        }
        validateHeaderValue(value, maximumLength);
        if (value.isBlank()) {
            return null;
        }
        return value;
    }

    private static void validateHeaderValue(String value, int maximumLength) {
        if (value.length() > maximumLength || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw authInvalid();
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        log.debug("[project-auth] 请求认证拒绝 category=AUTH_INVALID");
        if (response.isCommitted()) {
            log.debug("[project-auth] 认证拒绝响应已提交 category=RESPONSE_COMMITTED");
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), CommonResult.error(BUYER_AUTH_INVALID));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ServiceException authInvalid() {
        return exception(BUYER_AUTH_INVALID);
    }

    private static ServiceException authInvalid(Throwable cause) {
        ServiceException failure = authInvalid();
        failure.initCause(cause);
        return failure;
    }

    private record ParsedHeaders(String appId, int keyVersion, String contractVersion,
                                 String operationCode, long timestamp, String nonce,
                                 String requestSha256, String signature,
                                 String buyerUserId, String channelCode) {
    }
}
