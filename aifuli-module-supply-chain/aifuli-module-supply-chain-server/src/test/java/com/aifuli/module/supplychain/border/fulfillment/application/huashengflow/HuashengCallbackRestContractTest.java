package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.interfaceapi.callback.HuashengCallbackController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 华昇公网回调 REST 控制器契约测试。
 *
 * <p>锁定规范要求：
 * <ul>
 *   <li>端点路径为 {@code POST /callbacks/suppliers/huasheng/{connectorVersionId}}，格式为 {@code text/plain}；</li>
 *   <li>成功提交或已成功幂等确认固定返回 HTTP 200 正文 {@code "success"}；</li>
 *   <li>400/404/409/500/503 绝不包装业务 200 JSON；</li>
 *   <li>薄控制器架构：仅依赖应用服务，禁止直连 DAL Mapper 或 HTTP 外呼。</li>
 * </ul>
 *
 * <p>设计锚点：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#81-冻结身份认证
 * 及 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#82-持久防重与收敛。
 */
class HuashengCallbackRestContractTest {

    private HuashengCallbackApplicationService applicationService;

    @Test
    void rejectionDoesNotEchoOrLogUntrustedExceptionText() throws Exception {
        String marker = "CARD_SECRET_SENTINEL";
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HuashengCallbackController.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            doThrow(new HuashengCallbackValidationException(marker)).when(applicationService)
                    .handleCallback(anyLong(), any(), any(), any(InputStream.class), any());
            var result = mockMvc.perform(post("/callbacks/suppliers/huasheng/200").content("hex"))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(marker);
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(marker);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    private MockMvc mockMvc;

    @Test
    void unexpectedFailureKeepsSafeCausalStackWithoutSensitiveMessages() throws Exception {
        String marker = "CARD_PRIVATE_UNEXPECTED_FAILURE";
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HuashengCallbackController.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            doThrow(new IllegalStateException(marker, new java.sql.SQLException(marker))).when(applicationService)
                    .handleCallback(any(), any(), any(), any(InputStream.class), any());
            mockMvc.perform(post("/callbacks/suppliers/huasheng/200").content("hex"))
                    .andExpect(status().isInternalServerError()).andExpect(content().string("Internal Server Error"));
            assertThat(logs.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(marker).contains("IllegalStateException", "SQLException");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @BeforeEach
    void setUp() {
        applicationService = mock(HuashengCallbackApplicationService.class);
        HuashengCallbackController controller = new HuashengCallbackController(applicationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("契约：成功提交或同内容已处理幂等返回 HTTP 200 text/plain 'success'")
    void successfulCallbackReturns200WithSuccessBody() throws Exception {
        doNothing().when(applicationService).handleCallback(
                eq(101L), eq("HS12345678901234"), eq("SIGN_ABC"), any(InputStream.class), eq("trace-123"));

        mockMvc.perform(post("/callbacks/suppliers/huasheng/101")
                        .header("Appid", "HS12345678901234")
                        .header("X-Sign", "SIGN_ABC")
                        .header("trace-id", "trace-123")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("0123456789abcdef"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("success"));
    }

    @Test
    @DisplayName("契约：校验异常（Appid不匹配/签名错误/时间窗口/格式错误等）返回 HTTP 400，绝不返回 200")
    void validationExceptionReturns400WithPlainText() throws Exception {
        doThrow(new HuashengCallbackValidationException("Invalid signature or timestamp"))
                .when(applicationService).handleCallback(any(), any(), any(), any(InputStream.class), any());

        mockMvc.perform(post("/callbacks/suppliers/huasheng/101")
                        .header("Appid", "HS12345678901234")
                        .header("X-Sign", "SIGN_BAD")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("0123456789abcdef"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Invalid callback"));
    }

    @Test
    @DisplayName("契约：未找到对应版本或原单返回 HTTP 404，绝不返回 200")
    void notFoundExceptionReturns404WithPlainText() throws Exception {
        doThrow(new HuashengCallbackNotFoundException("Connector version not found: 101"))
                .when(applicationService).handleCallback(any(), any(), any(), any(InputStream.class), any());

        mockMvc.perform(post("/callbacks/suppliers/huasheng/101")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("0123456789abcdef"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Callback resource not found"));
    }

    @Test
    @DisplayName("契约：nonce 冲突（同 nonce 异内容或改投版本）返回 HTTP 409，绝不返回 200")
    void conflictExceptionReturns409WithPlainText() throws Exception {
        doThrow(new HuashengCallbackConflictException("Duplicate nonce with differing payload digest"))
                .when(applicationService).handleCallback(any(), any(), any(), any(InputStream.class), any());

        mockMvc.perform(post("/callbacks/suppliers/huasheng/101")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("0123456789abcdef"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Callback conflict"));
    }

    @Test
    @DisplayName("契约：进程执行锁忙/超时返回 HTTP 503，无领域或 Inbox 写入")
    void lockBusyExceptionReturns503WithPlainText() throws Exception {
        doThrow(new HuashengLockBusyException("Timeout waiting for Huasheng execution lock"))
                .when(applicationService).handleCallback(any(), any(), any(), any(InputStream.class), any());

        mockMvc.perform(post("/callbacks/suppliers/huasheng/101")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("0123456789abcdef"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Callback busy"));
    }

    @Test
    @DisplayName("契约：内部数据库或未预料异常返回 HTTP 500")
    void internalExceptionReturns500WithPlainText() throws Exception {
        doThrow(new RuntimeException("Database error"))
                .when(applicationService).handleCallback(any(), any(), any(), any(InputStream.class), any());

        mockMvc.perform(post("/callbacks/suppliers/huasheng/101")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("0123456789abcdef"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Internal Server Error"));
    }

    @Test
    @DisplayName("架构契约：薄控制器仅依赖应用服务，具备 @RestController 与精确路径映射，无 @PreAuthorize")
    void controllerArchitectureContract() throws NoSuchMethodException {
        Class<HuashengCallbackController> clazz = HuashengCallbackController.class;
        assertThat(clazz.isAnnotationPresent(RestController.class)).isTrue();
        assertThat(clazz.isAnnotationPresent(RequestMapping.class)).isTrue();
        assertThat(clazz.getAnnotation(RequestMapping.class).value()).contains("/callbacks/suppliers/huasheng");

        // 薄控制器仅依赖应用服务
        Field[] fields = clazz.getDeclaredFields();
        assertThat(fields).allSatisfy(f -> {
            if (!f.getType().getName().startsWith("org.slf4j")) {
                assertThat(f.getType()).isEqualTo(HuashengCallbackApplicationService.class);
            }
        });

        // 映射方法必须为 POST /{connectorVersionId}
        var method = clazz.getMethod("handleCallback", Long.class, String.class, String.class, String.class, jakarta.servlet.http.HttpServletRequest.class);
        assertThat(method.isAnnotationPresent(PostMapping.class)).isTrue();
        assertThat(method.getAnnotation(PostMapping.class).value()).contains("/{connectorVersionId}");

        // 公开回调端点绝不可挂 @PreAuthorize（通过网关与安全配置进行 IP / 白名单与免登录 token 处理，业务层通过供应商认证把守）
        assertThat(clazz.isAnnotationPresent(PreAuthorize.class)).isFalse();
        assertThat(method.isAnnotationPresent(PreAuthorize.class)).isFalse();
    }
}
