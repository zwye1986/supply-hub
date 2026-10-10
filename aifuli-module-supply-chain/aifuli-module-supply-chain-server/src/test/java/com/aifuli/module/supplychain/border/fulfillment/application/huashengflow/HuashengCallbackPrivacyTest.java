package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.apilog.core.filter.ApiAccessLogFilter;
import com.aifuli.framework.apilog.core.interceptor.ApiAccessLogInterceptor;
import com.aifuli.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import com.aifuli.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import com.aifuli.framework.web.config.WebProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 华昇回调真实 Servlet 访问日志过滤器与拦截器保护测试。
 *
 * <p>锁定规范要求：
 * <ul>
 *   <li>明确断言正文、参数、响应与异常日志中不存在夹具 token、X-Sign、hex 密文、code/pass；</li>
 *   <li>响应头强制添加 {@code Cache-Control: no-store}；</li>
 *   <li>相邻路径负控制（如 {@code /callbacks/suppliers/huasheng-other/101}）仍按既有规则记录安全内容。</li>
 * </ul>
 *
 * <p>设计锚点：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#83-安全与日志影响。
 */
class HuashengCallbackPrivacyTest {

    private static final String SENSITIVE_TOKEN = "FIXTURE_TOKEN_SECRET_999";
    private static final String SENSITIVE_SIGN = "FIXTURE_X_SIGN_HEX_ABCD1234";
    private static final String SENSITIVE_CIPHER = "0123456789ABCDEF_HEX_CIPHER_CARD_SECRET_DATA_RAW";
    private static final String SENSITIVE_CODE = "TEST_CARD_CODE_8888";
    private static final String SENSITIVE_PASS = "TEST_CARD_PASS_9999";

    private ApiAccessLogCommonApi accessLogApi;
    private WebProperties webProperties;
    private ApiAccessLogFilter filter;
    private ApiAccessLogInterceptor interceptor;

    private ListAppender<ILoggingEvent> appender;
    private Logger filterLogger;
    private Logger interceptorLogger;

    @BeforeEach
    void setUp() {
        accessLogApi = mock(ApiAccessLogCommonApi.class);
        webProperties = new WebProperties();
        filter = new ApiAccessLogFilter(webProperties, "supply-chain-server", accessLogApi);
        interceptor = new ApiAccessLogInterceptor();

        appender = new ListAppender<>();
        appender.start();

        filterLogger = (Logger) LoggerFactory.getLogger(ApiAccessLogFilter.class);
        interceptorLogger = (Logger) LoggerFactory.getLogger(ApiAccessLogInterceptor.class);
        filterLogger.addAppender(appender);
        interceptorLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        filterLogger.detachAppender(appender);
        interceptorLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("隐私保护：成功回调不记录请求参数、正文、X-Sign与卡密，设置 no-store 响应头")
    void successfulCallbackDoesNotLogSensitiveParamsBodyOrHeadersAndSetsNoStore() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callbacks/suppliers/huasheng/101");
        request.addHeader("X-Sign", SENSITIVE_SIGN);
        request.addHeader("Appid", "HS12345678901234");
        request.setParameter("token", SENSITIVE_TOKEN);
        request.setContent(SENSITIVE_CIPHER.getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();

        // 模拟拦截器 preHandle
        interceptor.preHandle(request, response, new Object());

        // 执行过滤器
        FilterChain chain = (req, res) -> {
            res.getWriter().write("success");
        };
        filter.doFilter(request, response, chain);

        // 模拟拦截器 afterCompletion
        interceptor.afterCompletion(request, response, new Object(), null);

        // 验证响应头
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");

        // 验证访问日志 DTO 绝不含敏感凭据与密文
        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(accessLogApi).createApiAccessLogAsync(captor.capture());
        ApiAccessLogCreateReqDTO logDto = captor.getValue();

        assertThat(logDto.getRequestUrl()).isEqualTo("/callbacks/suppliers/huasheng/101");
        assertThat(logDto.getRequestParams()).isNull();
        assertThat(logDto.getResultMsg()).isEmpty();

        // 验证 logger 事件中绝无任何敏感信息
        for (ILoggingEvent event : appender.list) {
            String msg = event.getFormattedMessage();
            assertThat(msg).doesNotContain(SENSITIVE_TOKEN);
            assertThat(msg).doesNotContain(SENSITIVE_SIGN);
            assertThat(msg).doesNotContain(SENSITIVE_CIPHER);
            assertThat(msg).doesNotContain(SENSITIVE_CODE);
            assertThat(msg).doesNotContain(SENSITIVE_PASS);
        }
    }

    @Test
    @DisplayName("隐私保护：回调处理发生异常时不泄露堆栈敏感信息（卡号、密码、X-Sign、密文）")
    void failedCallbackDoesNotLeakSensitiveExceptionMessageOrCardData() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callbacks/suppliers/huasheng/101");
        request.addHeader("X-Sign", SENSITIVE_SIGN);
        request.setParameter("token", SENSITIVE_TOKEN);
        request.setContent(SENSITIVE_CIPHER.getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        FilterChain failingChain = (req, res) -> {
            throw new RuntimeException("Card allocation failure: code=" + SENSITIVE_CODE
                    + ", pass=" + SENSITIVE_PASS + ", sign=" + SENSITIVE_SIGN);
        };

        try {
            filter.doFilter(request, response, failingChain);
        } catch (Exception ignored) {
            // filter 重新抛出异常属正常行为
        }

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");

        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(accessLogApi).createApiAccessLogAsync(captor.capture());
        ApiAccessLogCreateReqDTO logDto = captor.getValue();

        assertThat(logDto.getResultCode()).isEqualTo(500);
        // 关键断言：resultMsg 仅记录安全异常类型，绝不包含敏感异常详情
        assertThat(logDto.getResultMsg()).isEqualTo("java.lang.RuntimeException");
        assertThat(logDto.getResultMsg()).doesNotContain(SENSITIVE_CODE);
        assertThat(logDto.getResultMsg()).doesNotContain(SENSITIVE_PASS);
        assertThat(logDto.getResultMsg()).doesNotContain(SENSITIVE_SIGN);

        for (ILoggingEvent event : appender.list) {
            String msg = event.getFormattedMessage();
            assertThat(msg).doesNotContain(SENSITIVE_TOKEN);
            assertThat(msg).doesNotContain(SENSITIVE_SIGN);
            assertThat(msg).doesNotContain(SENSITIVE_CIPHER);
            assertThat(msg).doesNotContain(SENSITIVE_CODE);
            assertThat(msg).doesNotContain(SENSITIVE_PASS);
        }
    }

    @Test
    @DisplayName("负控制：相邻路径 huasheng-other 正常记录参数，不被过度脱敏误伤")
    void adjacentPathNegativeControlPreservesNormalLogging() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callbacks/suppliers/huasheng-other/101");
        request.setParameter("normalParam", "safe_value_abc");
        request.setContent("{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        FilterChain chain = (req, res) -> res.getWriter().write("ok");
        filter.doFilter(request, response, chain);
        interceptor.afterCompletion(request, response, new Object(), null);

        // 1. 相邻非敏感路径不设置 no-store
        assertThat(response.getHeader("Cache-Control")).isNull();

        // 2. 拦截器按正常规则输出参数日志
        boolean foundLoggedParam = appender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("safe_value_abc"));
        assertThat(foundLoggedParam).isTrue();

        // 3. 过滤器对受控 API 路径正常记录参数
        MockHttpServletRequest adminReq = new MockHttpServletRequest("POST", "/admin-api/supply-chain/supplier-config/other");
        adminReq.setParameter("adminParam", "safe_admin_123");
        MockHttpServletResponse adminRes = new MockHttpServletResponse();
        filter.doFilter(adminReq, adminRes, (req, res) -> res.getWriter().write("ok"));

        ArgumentCaptor<ApiAccessLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
        verify(accessLogApi).createApiAccessLogAsync(captor.capture());
        ApiAccessLogCreateReqDTO logDto = captor.getValue();

        assertThat(logDto.getRequestParams()).isNotNull();
        assertThat(logDto.getRequestParams()).contains("safe_admin_123");
    }
}
