package com.aifuli.module.supplychain.infrastructure.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.apilog.core.annotation.ApiAccessLog;
import com.aifuli.framework.apilog.core.filter.ApiAccessLogFilter;
import com.aifuli.framework.apilog.core.interceptor.ApiAccessLogInterceptor;
import com.aifuli.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import com.aifuli.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import com.aifuli.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import com.aifuli.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import com.aifuli.framework.common.util.http.ApiAccessLogPathUtils;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.web.core.util.WebFrameworkUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.aifuli.framework.common.util.spring.SpringUtils;
import com.aifuli.framework.web.config.WebProperties;
import com.aifuli.framework.web.core.filter.CacheRequestBodyWrapper;
import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 凭据写入不得泄漏到共享日志链路；见
 * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#151-凭据写入日志保护。 */
class SupplierCredentialLoggingTest {
    private static final String PATH = "/admin-api/supply-chain/supplier-config/credential-items";
    private static final String SECRET = "SYNTHETIC_CREDENTIAL_SECRET";

    @Test
    void credentialPathsAreProtectedWithContextAndAdjacentPathsStayOrdinary() {
        for (String path : new String[]{PATH, "/supply-chain/supplier-config/credential-items",
                "/admin-api/supply-chain/supplier-config/credential-slots",
                "/admin-api/supply-chain/supplier-config/connectors/drafts",
                "/supply-chain/supplier-config/connectors/drafts/child"}) {
            assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(path)).isTrue();
            assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath("/sc" + path, "/sc")).isTrue();
        }
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(PATH + "-other")).isFalse();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath("/admin-api/supply-chain/supplier-config/connectors/drafts-other"))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {PATH, "/admin-api/supply-chain/supplier-config/connectors/drafts",
            "/supply-chain/supplier-config/connectors/drafts/child"})
    void filterOmitsBodyAndQueryWithoutConsumingForwardedBytes(String path) throws Exception {
        ApiAccessLogCommonApi api = mock(ApiAccessLogCommonApi.class);
        WebProperties properties = new WebProperties();
        // 裸路径部署仍通过实际 Filter：配置对应 API 前缀，避免默认 /admin-api 跳过测试链。
        if (path.startsWith("/supply-chain/")) properties.getAdminApi().setPrefix("/supply-chain");
        ApiAccessLogFilter filter = new ApiAccessLogFilter(properties, "supply-chain-server", api);
        for (String body : new String[]{"{\"extraConfig\":\"{}\"}", "{\"secretValue\":\"" + SECRET + "\"}", "{invalid:" + SECRET,
                "{\"extraConfig\":\"{\\\"token\\\":\\\"" + SECRET + "\\\"}\"}",
                "{\"extraConfig\":\"{invalid:" + SECRET + "\"}"}) {
            MockHttpServletRequest raw = request("/sc" + path, body);
            raw.setContextPath("/sc");
            MockHttpServletResponse response = new MockHttpServletResponse();
            raw.setAttribute(ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD,
                    new HandlerMethod(new ResponseLoggingController(), ResponseLoggingController.class.getMethod("write")));
            filter.doFilter(new CacheRequestBodyWrapper(raw), response, (req, res) -> {
                WebFrameworkUtils.setCommonResult(req, CommonResult.success(SECRET));
                assertThat(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(body);
            });
            ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
            verify(api, atLeastOnce()).createApiAccessLogAsync(captor.capture());
            assertThat(captor.getValue().getRequestParams()).isNull();
            assertThat(captor.getValue().getResponseBody()).isNull();
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {PATH, "/admin-api/supply-chain/supplier-config/connectors/drafts",
            "/supply-chain/supplier-config/connectors/drafts/child"})
    void nonProductionInterceptorDoesNotPrintPlaintext(String path) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiAccessLogInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try (var mocked = mockStatic(SpringUtils.class)) {
            mocked.when(SpringUtils::isProd).thenReturn(false);
            new ApiAccessLogInterceptor().preHandle(new CacheRequestBodyWrapper(request(path,
                    "{\"extraConfig\":\"{\\\"token\\\":\\\"" + SECRET + "\\\"}\"}")), new MockHttpServletResponse(), null);
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(SECRET));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {PATH, "/admin-api/supply-chain/supplier-config/connectors/drafts",
            "/supply-chain/supplier-config/connectors/drafts/child"})
    void systemFailureOmitsCredentialBodyAndExceptionTextFromErrorLog(String path) {
        ApiErrorLogCommonApi api = mock(ApiErrorLogCommonApi.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler("supply-chain-server", api);
        MockHttpServletRequest request = request(path, "{\"extraConfig\":\"{\\\"token\\\":\\\"" + SECRET + "\\\"}\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        try {
            assertThat(handler.allExceptionHandler(request, new IllegalStateException(SECRET)).getCode()).isEqualTo(500);
            ArgumentCaptor<ApiErrorLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
            verify(api).createApiErrorLogAsync(captor.capture());
            assertThat(captor.getValue().getRequestParams()).isEqualTo("{}");
            assertThat(captor.getValue().getExceptionMessage()).doesNotContain(SECRET);
            assertThat(captor.getValue().getExceptionStackTrace()).doesNotContain(SECRET);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {PATH, "/admin-api/supply-chain/supplier-config/connectors/drafts",
            "/supply-chain/supplier-config/connectors/drafts/child"})
    void invalidCredentialJsonReturnsSafeParameterError(String path) {
        GlobalExceptionHandler handler = new GlobalExceptionHandler("supply-chain-server", mock(ApiErrorLogCommonApi.class));
        MockHttpServletRequest request = request(path, "{invalid:" + SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            var result = handler.allExceptionHandler(request, new HttpMessageNotReadableException(SECRET));
            assertThat(result.getCode()).isEqualTo(400);
            assertThat(result.getMsg()).doesNotContain(SECRET);
            assertThat(appender.list).isNotEmpty().allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).doesNotContain(SECRET));
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        } finally {
            RequestContextHolder.resetRequestAttributes(); logger.detachAppender(appender); appender.stop();
        }
    }


    /** 真正封闭 Schema 拒绝及字符串解析失败仍走同一安全 Filter/异常处理链。 */
    @ParameterizedTest
    @ValueSource(strings = {"{\"token\":\"SYNTHETIC_CREDENTIAL_SECRET\"}", "{invalid:SYNTHETIC_CREDENTIAL_SECRET"})
    void draftSchemaAndMalformedExtraConfigRejectWithoutLoggingMaterial(String extra) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String body = json.writeValueAsString(java.util.Map.of("extraConfig", extra));
        var raw = request("/sc/admin-api/supply-chain/supplier-config/connectors/drafts", body);
        raw.setContextPath("/sc");
        var response = new MockHttpServletResponse();
        var accessApi = mock(ApiAccessLogCommonApi.class);
        var errorApi = mock(ApiErrorLogCommonApi.class);
        var handler = new GlobalExceptionHandler("supply-chain-server", errorApi);
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(raw, response));
        try {
            new ApiAccessLogFilter(new WebProperties(), "supply-chain-server", accessApi)
                    .doFilter(new CacheRequestBodyWrapper(raw), response, (req, res) -> {
                        assertThat(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(body);
                        Exception rejection;
                        try {
                            new com.aifuli.module.supplychain.supplieradapter.metadata.ClosedJsonSchemaValidator(json)
                                    .validate("supply-chain/adapter-schemas/qnm-game-order-v1-connector.schema.json",
                                            json.readTree(json.readTree(body).get("extraConfig").asText()));
                            throw new AssertionError("Invalid extraConfig unexpectedly passed Schema");
                        } catch (Exception failure) {
                            rejection = new IllegalArgumentException("Invalid extraConfig", failure);
                        }
                        var result = handler.allExceptionHandler(raw, rejection);
                        // 保留现有 Schema IllegalArgumentException 的系统错误码；保护不得改变领域错误映射。
                        assertThat(result.getCode()).isEqualTo(500);
                        assertThat(result.getMsg()).doesNotContain(SECRET);
                        WebFrameworkUtils.setCommonResult(req, result);
                    });
            var logs = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
            verify(accessApi).createApiAccessLogAsync(logs.capture());
            assertThat(logs.getValue().getRequestParams()).isNull();
            assertThat(logs.getValue().getResponseBody()).isNull();
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
                assertThat(event.getThrowableProxy()).isNull();
            });
            var errors = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
            verify(errorApi).createApiErrorLogAsync(errors.capture());
            assertThat(errors.getValue().getRequestParams()).isEqualTo("{}");
            assertThat(errors.getValue().getExceptionMessage()).doesNotContain(SECRET);
            assertThat(errors.getValue().getExceptionRootCauseMessage()).doesNotContain(SECRET);
            assertThat(errors.getValue().getExceptionStackTrace()).doesNotContain(SECRET);
        } finally {
            RequestContextHolder.resetRequestAttributes(); logger.detachAppender(appender); appender.stop();
        }
    }

    /** 即使控制器主动开启响应日志，凭据路径仍须强制省略。 */
    public static class ResponseLoggingController {
        @ApiAccessLog(responseEnable = true)
        public void write() { }
    }

    private static MockHttpServletRequest request(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addParameter("secretValue", SECRET);
        return request;
    }
}
