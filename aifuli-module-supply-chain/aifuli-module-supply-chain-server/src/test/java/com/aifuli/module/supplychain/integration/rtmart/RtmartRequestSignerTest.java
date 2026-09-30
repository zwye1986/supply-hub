package com.aifuli.module.supplychain.integration.rtmart;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 旅通请求签名与配置边界测试；全部密钥均在测试运行时生成，禁止使用任何真实渠道秘密。
 *
 * <p>覆盖范围与安全约束见
 * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#4-旅通请求协议}
 * 及 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#7-配置与秘密管理}。</p>
 */
class RtmartRequestSignerTest {

    private static final String APP_KEY = "rtmart-test-app-key";
    private static final String APP_KEY_SENTINEL = "APP-KEY-SECRET-SENTINEL";
    private static final String PRIVATE_KEY_SENTINEL = "PRIVATE-KEY-SECRET-SENTINEL";
    private static final String BASE_URL_SENTINEL = "BASE-URL-SECRET-SENTINEL";
    private static final String PATH_SENTINEL = "PATH-SECRET-SENTINEL";
    private static final String CAUSE_SECRET_SENTINEL = "CAUSE-SECRET-SENTINEL";
    private static final String UNKNOWN_REMOTE_CODE = "UNKNOWN";
    private static final String FIXED_NONCE = "0123456789abcdef0123456789abcdef";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-24T01:02:03Z");

    private KeyPair keyPair;
    private PrivateKey privateKey;
    private Clock clock;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        privateKey = keyPair.getPrivate();
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }

    @Test
    void signsSortedNonNullFieldsAndIndependentPublicKeyVerifiesSignature() throws Exception {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("zeta", "Z");
        request.put("token", "token-value");
        request.put("alpha", "A");
        request.put("company_name", "大润发上海店");
        request.put("sign", "caller-sign-must-be-replaced");
        request.put("nullValue", null);
        request.put("emptyValue", "");

        Map<String, String> signed = new RtmartRequestSigner(APP_KEY, privateKey, clock, () -> FIXED_NONCE)
                .sign(request);

        assertThat(signed).containsEntry("app_key", APP_KEY)
                .containsEntry("nonce_str", FIXED_NONCE)
                .containsEntry("timestamp", "1787533323")
                .containsEntry("emptyValue", "")
                .containsKeys("alpha", "token", "zeta", "sign")
                .doesNotContainKey("nullValue");
        assertThat(signed.get("sign")).isNotEqualTo("caller-sign-must-be-replaced");
        assertThat(verify(signed, "alphaAapp_key" + APP_KEY + "company_name大润发上海店emptyValue"
                + "nonce_str" + FIXED_NONCE
                + "timestamp1787533323token" + "token-valuezetaZ")).isTrue();
    }

    @Test
    void leavesInputUnchangedAndReturnsImmutableMap() {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("token", "token-value");
        request.put("sign", "caller-sign");
        Map<String, String> original = new LinkedHashMap<>(request);

        Map<String, String> signed = new RtmartRequestSigner(APP_KEY, privateKey, clock, () -> FIXED_NONCE)
                .sign(request);

        assertThat(request).containsExactlyEntriesOf(original);
        assertThatThrownBy(() -> signed.put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void productionNonceIsSecureFormat() {
        Map<String, String> signed = RtmartRequestSigner.production(APP_KEY, privateKey, clock)
                .sign(Map.of("token", "token-value"));

        assertThat(signed.get("nonce_str")).matches("[0-9a-f]{32}");
    }

    @Test
    void fixedClockAndNonceProduceIdenticalUtf8SignaturesForChineseBusinessValues() throws Exception {
        RtmartRequestSigner signer = new RtmartRequestSigner(APP_KEY, privateKey, clock, () -> FIXED_NONCE);
        Map<String, String> request = Map.of("company_name", "大润发上海店");

        Map<String, String> first = signer.sign(request);
        Map<String, String> second = signer.sign(request);

        assertThat(first).isEqualTo(second);
        assertThat(verify(first, "app_key" + APP_KEY + "company_name大润发上海店nonce_str" + FIXED_NONCE
                + "timestamp1787533323")).isTrue();
    }

    @Test
    void rejectsInvalidNonceWithoutLeakingSignatureInputs() {
        RtmartRequestSigner signer = new RtmartRequestSigner(APP_KEY_SENTINEL, privateKey, clock,
                () -> "INVALID-NONCE");

        assertThatThrownBy(() -> signer.sign(Map.of("token", PRIVATE_KEY_SENTINEL)))
                .isInstanceOf(RtmartEntryException.class)
                .extracting(error -> ((RtmartEntryException) error).getReason())
                .isEqualTo(RtmartEntryException.Reason.SIGNATURE);
        assertThatThrownBy(() -> signer.sign(Map.of("token", PRIVATE_KEY_SENTINEL)))
                .hasMessageNotContaining(APP_KEY_SENTINEL)
                .hasMessageNotContaining(PRIVATE_KEY_SENTINEL)
                .hasMessageNotContaining("INVALID-NONCE");
    }

    @Test
    void parsesPkcs8PrivateKeyFromPemAndBareBase64() throws Exception {
        String bareBase64 = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + bareBase64 + "\n-----END PRIVATE KEY-----";

        assertThat(RtmartPrivateKeyParser.parsePkcs8(pem).getEncoded()).isEqualTo(privateKey.getEncoded());
        assertThat(RtmartPrivateKeyParser.parsePkcs8(" \n" + bareBase64 + "\t").getEncoded())
                .isEqualTo(privateKey.getEncoded());
    }

    @Test
    void invalidPrivateKeyIsConfigurationErrorWithCauseAndNoSecretLeak() {
        String invalidPrivateKey = PRIVATE_KEY_SENTINEL + "-not-base64";

        RtmartEntryException exception = catchThrowableOfType(
                () -> RtmartPrivateKeyParser.parsePkcs8(invalidPrivateKey), RtmartEntryException.class);

        assertThat(exception.getReason()).isEqualTo(RtmartEntryException.Reason.CONFIGURATION);
        assertThat(exception).hasCauseInstanceOf(RtmartSafeCause.class)
                .hasMessageNotContaining(invalidPrivateKey)
                .hasMessageNotContaining(PRIVATE_KEY_SENTINEL);
        assertThat(((RtmartSafeCause) exception.getCause()).getOriginalType())
                .isEqualTo(IllegalArgumentException.class.getName());
        assertThrowableTreeDoesNotContain(exception, invalidPrivateKey, PRIVATE_KEY_SENTINEL);
    }

    @Test
    void privateKeyParserRetainsActualJcaCauseWithoutLeakingConfiguredValue() {
        String nonPkcs8Base64 = Base64.getEncoder()
                .encodeToString("not-a-pkcs8-private-key".getBytes(StandardCharsets.UTF_8));

        RtmartEntryException exception = catchThrowableOfType(
                () -> RtmartPrivateKeyParser.parsePkcs8(nonPkcs8Base64), RtmartEntryException.class);

        assertThat(exception).hasCauseInstanceOf(RtmartSafeCause.class);
        assertThat(((RtmartSafeCause) exception.getCause()).getOriginalType())
                .isEqualTo(InvalidKeySpecException.class.getName());
        assertThrowableTreeDoesNotContain(exception, nonPkcs8Base64);
    }

    @Test
    void privateKeyParserRejectsPartialRepeatedAndMixedPemBoundaries() {
        String bareBase64 = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        List<String> malformedPemValues = List.of(
                "-----BEGIN PRIVATE KEY-----\n" + bareBase64,
                bareBase64 + "\n-----END PRIVATE KEY-----",
                "-----BEGIN PRIVATE KEY-----\n-----BEGIN PRIVATE KEY-----\n" + bareBase64
                        + "\n-----END PRIVATE KEY-----\n-----END PRIVATE KEY-----",
                "-----BEGIN PRIVATE KEY-----\n" + bareBase64 + "\n-----END RSA PRIVATE KEY-----");

        malformedPemValues.forEach(value -> assertThatThrownBy(() -> RtmartPrivateKeyParser.parsePkcs8(value))
                .isInstanceOf(RtmartEntryException.class)
                .extracting(error -> ((RtmartEntryException) error).getReason())
                .isEqualTo(RtmartEntryException.Reason.CONFIGURATION));
    }

    @Test
    void enabledPropertiesRequireSecretsAndRedactThemFromToString() {
        RtmartEntryProperties properties = validProperties();
        properties.setAppKey(APP_KEY_SENTINEL);
        properties.setPrivateKey(PRIVATE_KEY_SENTINEL);

        properties.validate();
        assertThat(properties.toString()).contains("enabled=true")
                .doesNotContain(APP_KEY_SENTINEL, PRIVATE_KEY_SENTINEL);
    }

    @Test
    void enabledPropertiesRejectMissingSecrets() {
        RtmartEntryProperties properties = validProperties();
        properties.setAppKey(" ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(RtmartEntryException.class)
                .hasMessageNotContaining("appKey");
    }

    @Test
    void enabledPropertiesRejectNonLoopbackHttpAndMalformedPaths() {
        RtmartEntryProperties httpProperties = validProperties();
        httpProperties.setBaseUrl("http://rtmart.example");

        assertThatThrownBy(httpProperties::validate)
                .isInstanceOf(RtmartEntryException.class);

        RtmartEntryProperties pathProperties = validProperties();
        pathProperties.setHomePath("https://attacker.example/path");

        assertThatThrownBy(pathProperties::validate)
                .isInstanceOf(RtmartEntryException.class);
    }

    @Test
    void enabledPropertiesAllowLoopbackHttpForMockWebServer() {
        RtmartEntryProperties properties = validProperties();
        properties.setBaseUrl("http://127.0.0.1:8080");

        properties.validate();
    }

    @Test
    void enabledPropertiesAllowOnlyExactHttpLoopbackLiteralsIncludingIpv6() {
        for (String baseUrl : List.of("http://127.0.0.1:8080", "http://[::1]:8080")) {
            RtmartEntryProperties properties = validProperties();
            properties.setBaseUrl(baseUrl);

            properties.validate();
        }

        for (String baseUrl : List.of("http://localhost:8080", "http://rtmart.example:8080",
                "http://127.0.0.2:8080", "http://127.0.0.1.:8080", "http://[::2]:8080")) {
            RtmartEntryProperties properties = validProperties();
            properties.setBaseUrl(baseUrl);

            assertThatThrownBy(properties::validate)
                    .isInstanceOf(RtmartEntryException.class)
                    .extracting(error -> ((RtmartEntryException) error).getReason())
                    .isEqualTo(RtmartEntryException.Reason.CONFIGURATION);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://test.ltsw.cn:0",
            "https://test.ltsw.cn:65536",
            "https://test.ltsw.cn:99999"
    })
    void enabledPropertiesRejectExplicitPortsOutsideTcpRange(String baseUrl) {
        RtmartEntryProperties properties = validProperties();
        properties.setBaseUrl(baseUrl);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(RtmartEntryException.class)
                .extracting(error -> ((RtmartEntryException) error).getReason())
                .isEqualTo(RtmartEntryException.Reason.CONFIGURATION);
    }

    @Test
    void malformedEndpointConfigurationDoesNotLeakValuesThroughCauseOrStackTrace() {
        RtmartEntryProperties malformedBaseUrl = validProperties();
        malformedBaseUrl.setBaseUrl("https://example.test/%ZZ" + BASE_URL_SENTINEL);

        RtmartEntryException baseUrlException = catchThrowableOfType(
                malformedBaseUrl::validate, RtmartEntryException.class);
        assertThat(baseUrlException).hasCauseInstanceOf(RtmartSafeCause.class);
        assertThat(((RtmartSafeCause) baseUrlException.getCause()).getOriginalType())
                .isEqualTo(IllegalArgumentException.class.getName());
        assertThrowableTreeDoesNotContain(baseUrlException, BASE_URL_SENTINEL);

        RtmartEntryProperties malformedPath = validProperties();
        malformedPath.setHomePath("/%ZZ" + PATH_SENTINEL);

        RtmartEntryException pathException = catchThrowableOfType(malformedPath::validate, RtmartEntryException.class);
        assertThat(pathException).hasCauseInstanceOf(RtmartSafeCause.class);
        assertThat(((RtmartSafeCause) pathException.getCause()).getOriginalType())
                .isEqualTo(IllegalArgumentException.class.getName());
        assertThrowableTreeDoesNotContain(pathException, PATH_SENTINEL);
    }

    @Test
    void safeCauseRecursivelyPreservesTypesStacksCauseAndSuppressedWithoutOriginalReferencesOrMessages() {
        IllegalStateException root = new IllegalStateException("root-" + CAUSE_SECRET_SENTINEL);
        IllegalArgumentException nested = new IllegalArgumentException("nested-" + CAUSE_SECRET_SENTINEL);
        IOException deepest = new IOException("deepest-" + CAUSE_SECRET_SENTINEL);
        UnsupportedOperationException suppressed = new UnsupportedOperationException(
                "suppressed-" + CAUSE_SECRET_SENTINEL);
        root.initCause(nested);
        nested.initCause(deepest);
        root.addSuppressed(suppressed);
        suppressed.addSuppressed(root); // 构造回环，证明安全副本不会无限递归。

        RtmartEntryException exception = RtmartEntryException.configuration(root);

        assertThat(exception).hasCauseInstanceOf(RtmartSafeCause.class);
        RtmartSafeCause safeRoot = (RtmartSafeCause) exception.getCause();
        assertThat(safeRoot.getCategory()).isEqualTo("configuration");
        assertThat(safeRoot.getOriginalType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(safeRoot.getStackTrace()).isEqualTo(java.util.Arrays.copyOf(root.getStackTrace(),
                Math.min(root.getStackTrace().length, RtmartSafeCause.MAX_SAFE_STACK_FRAMES_PER_NODE)));
        assertThat(safeRoot.getCause()).isInstanceOf(RtmartSafeCause.class);
        assertThat(((RtmartSafeCause) safeRoot.getCause()).getOriginalType())
                .isEqualTo(IllegalArgumentException.class.getName());
        assertThat(((RtmartSafeCause) safeRoot.getCause()).getCause()).isInstanceOf(RtmartSafeCause.class);
        assertThat(((RtmartSafeCause) ((RtmartSafeCause) safeRoot.getCause()).getCause()).getOriginalType())
                .isEqualTo(IOException.class.getName());
        assertThat(safeRoot.getSuppressed()).singleElement().isInstanceOf(RtmartSafeCause.class);
        assertThat(((RtmartSafeCause) safeRoot.getSuppressed()[0]).getOriginalType())
                .isEqualTo(UnsupportedOperationException.class.getName());
        assertThat(((RtmartSafeCause) safeRoot.getSuppressed()[0]).getSuppressed())
                .singleElement().isInstanceOf(RtmartSafeCause.class);

        String diagnostics = renderThrowableTree(exception);
        assertThat(diagnostics).contains(IllegalStateException.class.getName(), IllegalArgumentException.class.getName(),
                        IOException.class.getName(), UnsupportedOperationException.class.getName())
                .doesNotContain(CAUSE_SECRET_SENTINEL);
        assertOriginalThrowableReferencesAbsent(exception, Set.of(root, nested, deepest, suppressed));
    }

    @Test
    void safeCauseBoundsVeryDeepCauseTrees() {
        RuntimeException root = new RuntimeException("depth-0-" + CAUSE_SECRET_SENTINEL);
        RuntimeException cursor = root;
        for (int depth = 1; depth < 100; depth++) {
            RuntimeException next = new RuntimeException("depth-" + depth + '-' + CAUSE_SECRET_SENTINEL);
            cursor.initCause(next);
            cursor = next;
        }

        RtmartEntryException exception = RtmartEntryException.transport(root);

        assertThat(countThrowableNodes(exception)).isLessThanOrEqualTo(RtmartSafeCause.MAX_SAFE_DEPTH + 2);
        assertThrowableTreeDoesNotContain(exception, CAUSE_SECRET_SENTINEL);
    }

    @Test
    void safeCauseBoundsSuppressedBreadthAndAddsExplicitTruncationMarker() {
        RuntimeException root = new RuntimeException("root-" + CAUSE_SECRET_SENTINEL);
        Set<Throwable> originals = Collections.newSetFromMap(new IdentityHashMap<>());
        originals.add(root);
        for (int index = 0; index < RtmartSafeCause.MAX_SAFE_SUPPRESSED + 20; index++) {
            IllegalStateException suppressed = new IllegalStateException(
                    "suppressed-" + index + '-' + CAUSE_SECRET_SENTINEL);
            originals.add(suppressed);
            root.addSuppressed(suppressed);
        }

        RtmartEntryException exception = RtmartEntryException.configuration(root);

        RtmartSafeCause safeRoot = (RtmartSafeCause) exception.getCause();
        assertThat(safeRoot.getSuppressed()).hasSize(RtmartSafeCause.MAX_SAFE_SUPPRESSED + 1);
        assertThat(java.util.Arrays.copyOf(safeRoot.getSuppressed(), RtmartSafeCause.MAX_SAFE_SUPPRESSED))
                .allMatch(RtmartSafeCause.class::isInstance);
        RtmartSafeCause marker = (RtmartSafeCause) safeRoot.getSuppressed()[RtmartSafeCause.MAX_SAFE_SUPPRESSED];
        assertThat(marker.getCategory()).isEqualTo("configuration.suppressed-limit");
        assertThat(marker.getOriginalType()).isEqualTo(RtmartSafeCause.SUPPRESSED_LIMIT_MARKER_TYPE);
        assertThrowableTreeDoesNotContain(exception, CAUSE_SECRET_SENTINEL);
        assertOriginalThrowableReferencesAbsent(exception, originals);
    }

    @Test
    void safeCauseBoundsSharedDagExpansionAndTotalStackFrames() {
        RuntimeException shared = new RuntimeException("shared-leaf-" + CAUSE_SECRET_SENTINEL);
        shared.setStackTrace(syntheticStack("shared-leaf", 100));
        for (int depth = 0; depth < 10; depth++) {
            RuntimeException parent = new RuntimeException("shared-parent-" + depth + '-' + CAUSE_SECRET_SENTINEL);
            parent.setStackTrace(syntheticStack("shared-parent-" + depth, 100));
            for (int branch = 0; branch < RtmartSafeCause.MAX_SAFE_SUPPRESSED; branch++) {
                parent.addSuppressed(shared);
            }
            shared = parent;
        }

        RtmartEntryException dagException = RtmartEntryException.configuration(shared);

        assertThat(countThrowableNodes(dagException))
                .isLessThanOrEqualTo(RtmartSafeCause.MAX_SAFE_NODES + 2);
        assertThat(renderThrowableTree(dagException))
                .contains(".reference", ".node-limit")
                .doesNotContain(CAUSE_SECRET_SENTINEL);

        RuntimeException stackRoot = new RuntimeException("stack-root-" + CAUSE_SECRET_SENTINEL);
        stackRoot.setStackTrace(syntheticStack("stack-root", 100));
        RuntimeException cursor = stackRoot;
        for (int depth = 1; depth < RtmartSafeCause.MAX_SAFE_DEPTH; depth++) {
            RuntimeException next = new RuntimeException("stack-" + depth + '-' + CAUSE_SECRET_SENTINEL);
            next.setStackTrace(syntheticStack("stack-" + depth, 100));
            cursor.initCause(next);
            cursor = next;
        }

        RtmartEntryException stackException = RtmartEntryException.transport(stackRoot);

        assertThat(countStackFrames(stackException.getCause()))
                .isLessThanOrEqualTo(RtmartSafeCause.MAX_SAFE_STACK_FRAMES_TOTAL);
        assertThat(renderThrowableTree(stackException))
                .contains(".stack-limit")
                .doesNotContain(CAUSE_SECRET_SENTINEL);
    }

    @Test
    void nonceSupplierFailureUsesSafeCauseBoundary() {
        RtmartRequestSigner signer = new RtmartRequestSigner(APP_KEY, privateKey, clock, () -> {
            throw new IllegalStateException("nonce-" + CAUSE_SECRET_SENTINEL);
        });

        RtmartEntryException exception = catchThrowableOfType(
                () -> signer.sign(Map.of("token", "token-value")), RtmartEntryException.class);

        assertThat(exception.getReason()).isEqualTo(RtmartEntryException.Reason.SIGNATURE);
        assertThat(exception).hasCauseInstanceOf(RtmartSafeCause.class);
        assertThat(((RtmartSafeCause) exception.getCause()).getOriginalType())
                .isEqualTo(IllegalStateException.class.getName());
        assertThrowableTreeDoesNotContain(exception, CAUSE_SECRET_SENTINEL);
    }

    @Test
    void requestSignerAndFactoriesAreNotPublicExtensionPoints() throws Exception {
        assertThat(Modifier.isPublic(RtmartRequestSigner.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(RtmartRequestSigner.class
                .getDeclaredConstructor(String.class, PrivateKey.class, Clock.class, java.util.function.Supplier.class)
                .getModifiers())).isFalse();
        assertThat(Modifier.isPublic(RtmartEntryException.class
                .getDeclaredMethod("remoteError", String.class).getModifiers())).isFalse();
        assertThat(Modifier.isPublic(RtmartPrivateKeyParser.class.getModifiers())).isFalse();
    }

    @Test
    void productionFactoryAcceptsClockAndUsesItForTimestamp() throws Exception {
        Method production = RtmartRequestSigner.class
                .getDeclaredMethod("production", String.class, PrivateKey.class, Clock.class);

        RtmartRequestSigner signer = (RtmartRequestSigner) production.invoke(null, APP_KEY, privateKey, clock);
        assertThat(signer.sign(Map.of("token", "token-value"))).containsEntry("timestamp", "1787533323");
    }

    @Test
    void remoteCodeIsBoundedAndSanitizedBeforeItCanReachLogs() {
        assertThat(RtmartEntryException.remoteError("E4001").getRemoteCode()).isEqualTo("E4001");
        assertThat(RtmartEntryException.remoteError("E0000").getRemoteCode()).isEqualTo("E0000");
        assertThat(RtmartEntryException.remoteError("E4001\r\nAuthorization: Bearer token-secret")
                .getRemoteCode()).isEqualTo(UNKNOWN_REMOTE_CODE);
        assertThat(RtmartEntryException.remoteError("x".repeat(33)).getRemoteCode())
                .isEqualTo(UNKNOWN_REMOTE_CODE);
        assertThat(RtmartEntryException.remoteError("token=pretend-secret").getRemoteCode())
                .isEqualTo(UNKNOWN_REMOTE_CODE);
        assertThat(RtmartEntryException.remoteError("0123456789abcdef0123456789abcdef").getRemoteCode())
                .isEqualTo(UNKNOWN_REMOTE_CODE);
        assertThat(RtmartEntryException.remoteError("rtmartAppKey20260824").getRemoteCode())
                .isEqualTo(UNKNOWN_REMOTE_CODE);
        assertThat(RtmartEntryException.remoteError("E123").getRemoteCode()).isEqualTo(UNKNOWN_REMOTE_CODE);
        assertThat(RtmartEntryException.remoteError("E12345").getRemoteCode()).isEqualTo(UNKNOWN_REMOTE_CODE);
    }

    @Test
    void enabledPropertiesRejectNonPositiveDurationsAndUnreasonableResponseLimit() {
        RtmartEntryProperties durationProperties = validProperties();
        durationProperties.setReadTimeout(java.time.Duration.ZERO);

        assertThatThrownBy(durationProperties::validate)
                .isInstanceOf(RtmartEntryException.class);

        RtmartEntryProperties limitProperties = validProperties();
        limitProperties.setMaxResponseBytes(RtmartEntryProperties.MAX_REASONABLE_RESPONSE_BYTES + 1);

        assertThatThrownBy(limitProperties::validate)
                .isInstanceOf(RtmartEntryException.class);
    }

    @Test
    void enabledPropertiesRejectTimeoutsOutsideOkHttpMillisecondRange() {
        assertTimeoutRejected(RtmartEntryProperties::setConnectTimeout, Duration.ofNanos(1));
        assertTimeoutRejected(RtmartEntryProperties::setWriteTimeout, Duration.ofNanos(1));
        assertTimeoutRejected(RtmartEntryProperties::setReadTimeout, Duration.ofNanos(1));

        Duration exceedsOkHttpMaximum = Duration.ofMillis(Integer.MAX_VALUE).plusNanos(1L);
        assertTimeoutRejected(RtmartEntryProperties::setConnectTimeout, exceedsOkHttpMaximum);
        assertTimeoutRejected(RtmartEntryProperties::setWriteTimeout, exceedsOkHttpMaximum);
        assertTimeoutRejected(RtmartEntryProperties::setReadTimeout, exceedsOkHttpMaximum);
    }

    @Test
    void enabledPropertiesRejectTimeoutsWithSubMillisecondPrecision() {
        for (Duration timeout : List.of(Duration.ofNanos(1_500_000L), Duration.ofMillis(1L).plusNanos(1L))) {
            assertTimeoutRejected(RtmartEntryProperties::setConnectTimeout, timeout);
            assertTimeoutRejected(RtmartEntryProperties::setWriteTimeout, timeout);
            assertTimeoutRejected(RtmartEntryProperties::setReadTimeout, timeout);
        }
    }

    @Test
    void enabledPropertiesAllowExactOkHttpTimeoutBoundariesForEveryTimeout() {
        Duration minimum = Duration.ofMillis(1L);
        assertTimeoutAccepted(RtmartEntryProperties::setConnectTimeout, minimum);
        assertTimeoutAccepted(RtmartEntryProperties::setWriteTimeout, minimum);
        assertTimeoutAccepted(RtmartEntryProperties::setReadTimeout, minimum);

        Duration maximum = Duration.ofMillis(Integer.MAX_VALUE);
        assertTimeoutAccepted(RtmartEntryProperties::setConnectTimeout, maximum);
        assertTimeoutAccepted(RtmartEntryProperties::setWriteTimeout, maximum);
        assertTimeoutAccepted(RtmartEntryProperties::setReadTimeout, maximum);
    }

    @Test
    void enabledPropertiesRetainSafeArithmeticCauseWhenTimeoutMillisecondConversionOverflows() {
        RtmartEntryProperties properties = validProperties();
        properties.setAppKey(APP_KEY_SENTINEL);
        properties.setPrivateKey(PRIVATE_KEY_SENTINEL);
        properties.setConnectTimeout(Duration.ofSeconds(Long.MAX_VALUE));

        RtmartEntryException exception = catchThrowableOfType(properties::validate, RtmartEntryException.class);

        assertThat(exception.getReason()).isEqualTo(RtmartEntryException.Reason.CONFIGURATION);
        assertThat(exception).hasCauseInstanceOf(RtmartSafeCause.class);
        assertThat(((RtmartSafeCause) exception.getCause()).getOriginalType())
                .isEqualTo(ArithmeticException.class.getName());
        assertThrowableTreeDoesNotContain(exception, APP_KEY_SENTINEL, PRIVATE_KEY_SENTINEL);
    }

    private RtmartEntryProperties validProperties() {
        RtmartEntryProperties properties = new RtmartEntryProperties();
        properties.setEnabled(true);
        properties.setAppKey(APP_KEY);
        properties.setPrivateKey(Base64.getEncoder().encodeToString(privateKey.getEncoded()));
        return properties;
    }

    private boolean verify(Map<String, String> signed, String expectedCanonicalText) throws Exception {
        Map<String, String> unsigned = new LinkedHashMap<>(signed);
        String signature = unsigned.remove("sign");
        String canonicalText = unsigned.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + entry.getValue())
                .reduce("", String::concat);
        assertThat(canonicalText).isEqualTo(expectedCanonicalText);

        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(canonicalText.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(signature));
    }

    private void assertTimeoutRejected(java.util.function.BiConsumer<RtmartEntryProperties, Duration> timeoutSetter,
                                       Duration timeout) {
        RtmartEntryProperties properties = validProperties();
        timeoutSetter.accept(properties, timeout);

        assertThatThrownBy(properties::validate).isInstanceOf(RtmartEntryException.class)
                .extracting(error -> ((RtmartEntryException) error).getReason())
                .isEqualTo(RtmartEntryException.Reason.CONFIGURATION);
    }

    private void assertTimeoutAccepted(java.util.function.BiConsumer<RtmartEntryProperties, Duration> timeoutSetter,
                                       Duration timeout) {
        RtmartEntryProperties properties = validProperties();
        timeoutSetter.accept(properties, timeout);

        properties.validate();
    }

    private static void assertThrowableTreeDoesNotContain(Throwable throwable, String... sensitiveValues) {
        assertThat(renderThrowableTree(throwable)).doesNotContain(sensitiveValues);
    }

    private static String renderThrowableTree(Throwable throwable) {
        StringWriter stackTrace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stackTrace));
        return stackTrace.toString();
    }

    private static void assertOriginalThrowableReferencesAbsent(Throwable throwable, Set<Throwable> originals) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        pending.add(throwable);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            assertThat(originals).doesNotContain(current);
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
    }

    private static int countThrowableNodes(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        pending.add(throwable);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return visited.size();
    }

    private static int countStackFrames(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        pending.add(throwable);
        int frames = 0;
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            frames += current.getStackTrace().length;
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return frames;
    }

    private static StackTraceElement[] syntheticStack(String methodPrefix, int size) {
        StackTraceElement[] stack = new StackTraceElement[size];
        for (int index = 0; index < size; index++) {
            stack[index] = new StackTraceElement("example.SafeCauseSource", methodPrefix + index,
                    "SafeCauseSource.java", index + 1);
        }
        return stack;
    }
}
