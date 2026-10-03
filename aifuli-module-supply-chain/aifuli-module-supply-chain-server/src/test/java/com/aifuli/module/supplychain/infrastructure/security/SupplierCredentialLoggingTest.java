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
                "/admin-api/supply-chain/supplier-config/credential-slots"}) {
            assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(path)).isTrue();
            assertThat(ApiAccessLogPathUtils.isSensitiveProtectionPath("/sc" + path, "/sc")).isTrue();
        }
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath(PATH + "-other")).isFalse();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath("/admin-api/supply-chain/supplier-config/connectors/drafts"))
                .isFalse();
    }

    @Test
    void filterOmitsBodyAndQueryWithoutConsumingForwardedBytes() throws Exception {
        ApiAccessLogCommonApi api = mock(ApiAccessLogCommonApi.class);
        ApiAccessLogFilter filter = new ApiAccessLogFilter(new WebProperties(), "supply-chain-server", api);
        for (String body : new String[]{"{\"secretValue\":\"" + SECRET + "\"}", "{invalid:" + SECRET}) {
            MockHttpServletRequest raw = request("/sc" + PATH, body);
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

    @Test
    void nonProductionInterceptorDoesNotPrintPlaintext() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiAccessLogInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try (var mocked = mockStatic(SpringUtils.class)) {
            mocked.when(SpringUtils::isProd).thenReturn(false);
            new ApiAccessLogInterceptor().preHandle(new CacheRequestBodyWrapper(request(PATH,
                    "{\"secretValue\":\"" + SECRET + "\"}")), new MockHttpServletResponse(), null);
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(SECRET));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void systemFailureOmitsCredentialBodyAndExceptionTextFromErrorLog() {
        ApiErrorLogCommonApi api = mock(ApiErrorLogCommonApi.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler("supply-chain-server", api);
        MockHttpServletRequest request = request(PATH, "{\"secretValue\":\"" + SECRET + "\"}");
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

    @Test
    void invalidCredentialJsonReturnsSafeParameterError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler("supply-chain-server", mock(ApiErrorLogCommonApi.class));
        MockHttpServletRequest request = request(PATH, "{invalid:" + SECRET);
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
