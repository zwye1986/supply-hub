package com.aifuli.module.supplychain.api.auth;

import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feign 拦截器单测：模板最终方法/路径定位操作、替换认证头，并以 Signer 公共函数独立复核签名。
 * 详见 docs/superpowers/specs/2026-08-23-project-app-auth-design.md#7-签名客户端。
 */
class ProjectAppAuthFeignInterceptorTest {

    private static final String APP_ID = "app-feign-test";
    private static final String APP_KEY = "secret-key-for-feign-test";
    private static final String CHANNEL_PATH =
            "/rpc-api/supply-chain/instore/channel-command/createPaymentOrder";
    private static final String CALLBACK_PATH =
            "/rpc-api/supply-chain/buyer/instore/payment/confirmPayment";
    private static final Set<String> CORE_AUTH_HEADER_NAMES = Set.of(
            ProjectAppAuthSigner.HEADER_APP_ID,
            ProjectAppAuthSigner.HEADER_KEY_VERSION,
            ProjectAppAuthSigner.HEADER_CONTRACT_VERSION,
            ProjectAppAuthSigner.HEADER_OPERATION_CODE,
            ProjectAppAuthSigner.HEADER_TIMESTAMP,
            ProjectAppAuthSigner.HEADER_NONCE,
            ProjectAppAuthSigner.HEADER_REQUEST_SHA256,
            ProjectAppAuthSigner.HEADER_SIGNATURE);
    private static final Set<String> AUTH_HEADER_NAMES = Set.of(
            ProjectAppAuthSigner.HEADER_APP_ID,
            ProjectAppAuthSigner.HEADER_KEY_VERSION,
            ProjectAppAuthSigner.HEADER_CONTRACT_VERSION,
            ProjectAppAuthSigner.HEADER_OPERATION_CODE,
            ProjectAppAuthSigner.HEADER_TIMESTAMP,
            ProjectAppAuthSigner.HEADER_NONCE,
            ProjectAppAuthSigner.HEADER_REQUEST_SHA256,
            ProjectAppAuthSigner.HEADER_SIGNATURE,
            ProjectAppAuthSigner.HEADER_BUYER_USER_ID,
            ProjectAppAuthSigner.HEADER_CHANNEL_CODE);

    @Test
    void applySignsRegisteredRequestTemplateAndIgnoresQuery() {
        byte[] body = "{\"channelOrderId\":\"RT-1\"}".getBytes(StandardCharsets.UTF_8);
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri(CHANNEL_PATH + "?traceId=not-signed");
        template.body(body, StandardCharsets.UTF_8);

        new ProjectAppAuthFeignInterceptor(() ->
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 2, null, "RT_MART")).apply(template);

        Map<String, Collection<String>> headers = template.headers();
        assertExpectedAuthenticationHeadersPresentExactlyOnce(
                headers, ProjectAppAuthSigner.HEADER_CHANNEL_CODE);
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_APP_ID)).isEqualTo(APP_ID);
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_KEY_VERSION)).isEqualTo("2");
        assertThat(headers).doesNotContainKey(ProjectAppAuthSigner.HEADER_BUYER_USER_ID);
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_CHANNEL_CODE)).isEqualTo("RT_MART");
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_OPERATION_CODE))
                .isEqualTo("INSTORE_CREATE_PAYMENT_ORDER");

        String requestSha256 = first(headers, ProjectAppAuthSigner.HEADER_REQUEST_SHA256);
        assertThat(requestSha256).isEqualTo(ProjectAppAuthSigner.sha256Hex(body));
        String canonical = ProjectAppAuthSigner.canonicalString(APP_ID, "2",
                first(headers, ProjectAppAuthSigner.HEADER_CONTRACT_VERSION),
                first(headers, ProjectAppAuthSigner.HEADER_OPERATION_CODE),
                first(headers, ProjectAppAuthSigner.HEADER_TIMESTAMP),
                first(headers, ProjectAppAuthSigner.HEADER_NONCE),
                requestSha256, "",
                first(headers, ProjectAppAuthSigner.HEADER_CHANNEL_CODE));
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_SIGNATURE))
                .isEqualTo(ProjectAppAuthSigner.hmacSha256Base64Url(APP_KEY, canonical));
    }

    @Test
    void applySignsCallbackTemplateWithoutScopeAndBody() {
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri(CALLBACK_PATH);

        new ProjectAppAuthFeignInterceptor(() ->
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, null)).apply(template);

        Map<String, Collection<String>> headers = template.headers();
        assertExpectedAuthenticationHeadersPresentExactlyOnce(headers);
        assertThat(headers).doesNotContainKeys(ProjectAppAuthSigner.HEADER_BUYER_USER_ID,
                ProjectAppAuthSigner.HEADER_CHANNEL_CODE);
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_REQUEST_SHA256))
                .isEqualTo(ProjectAppAuthSigner.sha256Hex(null));
    }

    @Test
    void applyReplacesStaleAuthenticationHeaders() {
        byte[] body = "{\"channelOrderId\":\"RT-2\"}".getBytes(StandardCharsets.UTF_8);
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri(CHANNEL_PATH);
        template.body(body, StandardCharsets.UTF_8);
        AUTH_HEADER_NAMES.forEach(name -> template.header(name, "stale-" + name));

        new ProjectAppAuthFeignInterceptor(() ->
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 2, null, "RT_MART")).apply(template);

        Map<String, Collection<String>> headers = template.headers();
        assertExpectedAuthenticationHeadersPresentExactlyOnce(
                headers, ProjectAppAuthSigner.HEADER_CHANNEL_CODE);
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_APP_ID)).isEqualTo(APP_ID);
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_SIGNATURE)).doesNotStartWith("stale-");
        assertChannelSignatureIsValid(headers, body);
    }

    @Test
    void repeatedApplyKeepsOnlyLatestAuthenticationHeaders() {
        byte[] body = "{\"channelOrderId\":\"RT-3\"}".getBytes(StandardCharsets.UTF_8);
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri(CHANNEL_PATH);
        template.body(body, StandardCharsets.UTF_8);
        ProjectAppAuthFeignInterceptor interceptor = new ProjectAppAuthFeignInterceptor(() ->
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 2, null, "RT_MART"));

        interceptor.apply(template);
        String firstNonce = first(template.headers(), ProjectAppAuthSigner.HEADER_NONCE);
        interceptor.apply(template);

        Map<String, Collection<String>> headers = template.headers();
        assertExpectedAuthenticationHeadersPresentExactlyOnce(
                headers, ProjectAppAuthSigner.HEADER_CHANNEL_CODE);
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_NONCE)).isNotEqualTo(firstNonce);
        assertChannelSignatureIsValid(headers, body);
    }

    @Test
    void applySignsStandardTemplateWithEightHeadersAndRemovesStaleScopeHeaders() {
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri(ProjectAuthOperations.STANDARD_ORDERS_PATH + "/query");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        template.body(body, StandardCharsets.UTF_8);
        AUTH_HEADER_NAMES.forEach(name -> template.header(name, "stale-" + name));
        new ProjectAppAuthFeignInterceptor(() ->
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, null)).apply(template);
        Map<String, Collection<String>> headers = template.headers();
        assertExpectedAuthenticationHeadersPresentExactlyOnce(headers);
        String canonical = ProjectAppAuthSigner.canonicalString(APP_ID, "1", "1.0.0",
                ProjectAuthOperations.STANDARD_ORDER_QUERY,
                first(headers, ProjectAppAuthSigner.HEADER_TIMESTAMP),
                first(headers, ProjectAppAuthSigner.HEADER_NONCE),
                first(headers, ProjectAppAuthSigner.HEADER_REQUEST_SHA256), "", "");
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_SIGNATURE).equals(
                ProjectAppAuthSigner.hmacSha256Base64Url(APP_KEY, canonical))).isTrue();
    }

    @Test
    void applyRejectsUnregisteredPath() {
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.uri("/rpc-api/supply-chain/instore/channel-command/notRegistered");

        ProjectAppAuthFeignInterceptor interceptor = new ProjectAppAuthFeignInterceptor(() ->
                new ProjectAppAuthCredentials(APP_ID, APP_KEY, 1, null, "RT_MART"));

        assertThatThrownBy(() -> interceptor.apply(template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未登记的受保护操作");
    }

    private static void assertExpectedAuthenticationHeadersPresentExactlyOnce(
            Map<String, Collection<String>> headers, String... scopeHeaderNames) {
        Set<String> expectedHeaderNames = new HashSet<>(CORE_AUTH_HEADER_NAMES);
        expectedHeaderNames.addAll(Set.of(scopeHeaderNames));
        expectedHeaderNames.forEach(name -> assertThat(headers.get(name)).hasSize(1));
        assertThat(headers.keySet())
                .filteredOn(AUTH_HEADER_NAMES::contains)
                .containsExactlyInAnyOrderElementsOf(expectedHeaderNames);
    }

    private static void assertChannelSignatureIsValid(
            Map<String, Collection<String>> headers, byte[] body) {
        String requestSha256 = first(headers, ProjectAppAuthSigner.HEADER_REQUEST_SHA256);
        assertThat(requestSha256).isEqualTo(ProjectAppAuthSigner.sha256Hex(body));
        String canonical = ProjectAppAuthSigner.canonicalString(
                first(headers, ProjectAppAuthSigner.HEADER_APP_ID),
                first(headers, ProjectAppAuthSigner.HEADER_KEY_VERSION),
                first(headers, ProjectAppAuthSigner.HEADER_CONTRACT_VERSION),
                first(headers, ProjectAppAuthSigner.HEADER_OPERATION_CODE),
                first(headers, ProjectAppAuthSigner.HEADER_TIMESTAMP),
                first(headers, ProjectAppAuthSigner.HEADER_NONCE),
                requestSha256, "", first(headers, ProjectAppAuthSigner.HEADER_CHANNEL_CODE));
        assertThat(first(headers, ProjectAppAuthSigner.HEADER_SIGNATURE))
                .isEqualTo(ProjectAppAuthSigner.hmacSha256Base64Url(APP_KEY, canonical));
    }

    private static String first(Map<String, Collection<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }
}
