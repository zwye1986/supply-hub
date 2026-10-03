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

    @Test
    void secretValueAndSecretRefAreAlsoMaskedOnOrdinaryJsonPaths() {
        assertThat(AccessLogSanitizeUtils.sanitizeJsonBody("{\"secretValue\":\"" + SECRET
                + "\",\"nested\":{\"secretRef\":\"env:SECRET\"}}" ).toString())
                .doesNotContain(SECRET, "env:SECRET");
    }
}
