package com.aifuli.gateway.filter.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 凭据正文直接透传且不进网关日志，见
 * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#151-凭据写入日志保护。 */
class SupplierCredentialAccessLogTest {
    private static final String SECRET = "SYNTHETIC_GATEWAY_CREDENTIAL";

    @ParameterizedTest
    @ValueSource(strings = {"{\"secretValue\":\"SYNTHETIC_GATEWAY_CREDENTIAL\"}", "{invalid:SYNTHETIC_GATEWAY_CREDENTIAL"})
    void credentialBodyAndQueryNeverEnterGatewayLogAndBodyIsUnchanged(String body) {
        Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            AccessLogFilter filter = new AccessLogFilter();
            ReflectionTestUtils.setField(filter, "codecConfigurer", ServerCodecConfigurer.create());
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
                    "/admin-api/supply-chain/supplier-config/credential-items?secretValue=" + SECRET)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body));
            filter.filter(exchange, forwarded -> DataBufferUtils.join(forwarded.getRequest().getBody())
                    .flatMap(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(body);
                        return Mono.empty();
                    })).block();
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(SECRET));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void downstreamFailureDoesNotLogCredentialOrExceptionMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
                    "/supply-chain/supplier-config/credential-slots?secretRef=" + SECRET)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(SECRET));
            assertThatThrownBy(() -> new AccessLogFilter().filter(exchange,
                    forwarded -> Mono.error(new IllegalStateException(SECRET))).block())
                    .isInstanceOf(IllegalStateException.class);
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }


    /** 受保护正文不能依赖普通 JSON 递归脱敏；管理录入目标同样不得进日志，成功与异常都透传原字节。 */
    @ParameterizedTest
    @ValueSource(strings = {"/admin-api/supply-chain/supplier-config/connectors/drafts",
            "/supply-chain/supplier-config/connectors/drafts/child",
            "/admin-api/supply-chain/orders/entry/projects/PRJ_1",
            "/supply-chain/orders/entry/projects/PRJ_1"})
    void draftExtraConfigBodyResponseQueryAndFailureNeverEnterGatewayLogs(String path) {
        Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            for (String extra : new String[]{"{}", "{\"token\":\"" + SECRET + "\"}", "{invalid:" + SECRET}) {
                String body = com.aifuli.framework.common.util.json.JsonUtils.toJsonString(java.util.Map.of("extraConfig", extra));
                for (boolean failing : new boolean[]{false, true}) {
                    var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path + "?token=" + SECRET)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body));
                    AccessLogFilter filter = new AccessLogFilter();
                    ReflectionTestUtils.setField(filter, "codecConfigurer", ServerCodecConfigurer.create());
                    Mono<Void> execution = filter.filter(exchange, forwarded -> DataBufferUtils.join(forwarded.getRequest().getBody())
                            .flatMap(buffer -> {
                                byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes);
                                DataBufferUtils.release(buffer);
                                assertThat(bytes).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
                                return failing ? Mono.error(new IllegalStateException(SECRET))
                                        : forwarded.getResponse().writeWith(Mono.just(forwarded.getResponse().bufferFactory()
                                                .wrap(SECRET.getBytes(StandardCharsets.UTF_8))));
                            }));
                    if (failing) {
                        assertThatThrownBy(execution::block).isInstanceOf(IllegalStateException.class).hasMessage(SECRET);
                    } else {
                        execution.block();
                        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(SECRET);
                    }
                }
            }
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void secretValueAndSecretRefAreAlsoMaskedOnOrdinaryJsonPaths() {
        assertThat(AccessLogSanitizeUtils.sanitizeJsonBody("{\"secretValue\":\"" + SECRET
                + "\",\"nested\":{\"secretRef\":\"env:SECRET\"}}" ).toString())
                .doesNotContain(SECRET, "env:SECRET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/callbacks/suppliers/huasheng", "/callbacks/suppliers/huasheng/101",
            "/callbacks/suppliers/renyue/SYNTHETIC_GATEWAY_CREDENTIAL",
            "/callbacks/suppliers/renyue/SYNTHETIC_GATEWAY_CREDENTIAL/extra",
            "/open-api/supply-chain/project/v2/orders", "/open-api/supply-chain/project/v2/orders/query",
            "/open-api/supply-chain/project/v2/orders/assets/query"})
    void sensitiveTransportBodyResponseQueryAndFailureNeverEnterGatewayLogs(String path) {
        Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            boolean renyue = path.startsWith("/callbacks/suppliers/renyue/");
            String body = renyue ? "RechargeAccount=13800138000&ReturnSign=%2B%2F%3D" + SECRET
                    : "0123456789abcdefhexCIPHER" + SECRET;
            for (boolean failing : new boolean[]{false, true}) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path + "?sign=" + SECRET)
                        .contentType(renyue ? org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED
                                : org.springframework.http.MediaType.TEXT_PLAIN).body(body));
                AccessLogFilter filter = new AccessLogFilter();
                ReflectionTestUtils.setField(filter, "codecConfigurer", ServerCodecConfigurer.create());
                Mono<Void> execution = filter.filter(exchange, forwarded -> DataBufferUtils.join(forwarded.getRequest().getBody())
                        .flatMap(buffer -> {
                            byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes);
                            DataBufferUtils.release(buffer);
                            assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(body);
                            return failing ? Mono.error(new IllegalStateException(SECRET))
                                    : forwarded.getResponse().writeWith(Mono.just(forwarded.getResponse().bufferFactory()
                                            .wrap("success".getBytes(StandardCharsets.UTF_8))));
                        }));
                if (failing) {
                    assertThatThrownBy(execution::block).isInstanceOf(IllegalStateException.class).hasMessage(SECRET);
                } else {
                    execution.block();
                    assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("success");
                }
            }
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET, "13800138000");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    /** 未到下游前绝不订阅 form 正文，也不读取 query；响应不装饰，不能改变签名原文。 */
    @Test
    void renyueFormBodyAndQueryAreNotReadByGatewayFilter() {
        var request = MockServerHttpRequest.post("/callbacks/suppliers/renyue/101?ReturnSign=" + SECRET)
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .body(reactor.core.publisher.Flux.defer(() -> { throw new AssertionError("不能订阅正文"); }));
        var exchange = MockServerWebExchange.from(request);
        var guarded = new org.springframework.http.server.reactive.ServerHttpRequestDecorator(request) {
            @Override public org.springframework.util.MultiValueMap<String, String> getQueryParams() {
                throw new AssertionError("不能读取查询参数");
            }
        };
        new AccessLogFilter().filter(exchange.mutate().request(guarded).build(), forwarded -> {
            assertThat(forwarded.getRequest().getBody()).isSameAs(request.getBody());
            assertThat(forwarded.getResponse()).isSameAs(exchange.getResponse());
            return Mono.empty();
        }).block();
    }

    @ParameterizedTest
    @ValueSource(strings = {"huasheng/101", "renyue/SYNTHETIC_GATEWAY_CREDENTIAL"})
    void supplierCallbackExceptionInGlobalExceptionHandlerMasksUrlAndHidesExceptionDetails(String suffix) {
        com.aifuli.gateway.handler.GlobalExceptionHandler handler = new com.aifuli.gateway.handler.GlobalExceptionHandler();
        Logger logger = (Logger) LoggerFactory.getLogger(com.aifuli.gateway.handler.GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            for (Throwable failure : new Throwable[]{new RuntimeException(SECRET),
                    new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, SECRET)}) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(
                        "/callbacks/suppliers/" + suffix + "?token=" + SECRET)
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN).body("CIPHER_" + SECRET));
                handler.handle(exchange, failure).block();
                assertThat(exchange.getResponse().getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
                assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain(SECRET);
            }
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
                assertThat(event.getFormattedMessage()).contains("/callbacks/suppliers/" +
                        (suffix.startsWith("huasheng") ? "huasheng/101" : "renyue/[version]"));
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
